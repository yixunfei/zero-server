package group.zn.zero.codegen.scaffold;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚手架 ownership manifest 的只读解析与受控渲染。
 *
 * <p>该类型不依赖 JSON 库，只用受限的单行文件记录语法解析 {@code zero-scaffold.json} 的
 * {@code files} 数组，以便生成区/用户区的基线比较在零额外运行时依赖下进行。
 * manifest 自身在记录中的 hash 为空，避免自引用循环。</p>
 *
 * <p>线程安全性：实例不可变；解析与渲染方法均为纯函数。</p>
 *
 * @author zn
 */
public final class ScaffoldOwnershipManifest {

    /** 当前 ownership schema 版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** manifest 在受控文件列表中的固定相对路径。 */
    public static final String PATH = "zero-scaffold.json";
    /** manifest 自身 hash 留空的值。 */
    public static final String SELF_HASH = "";

    /** 文件记录的受限语法：单行对象，四个必填字段。 */
    private static final Pattern ENTRY = Pattern.compile(
            "^\\s*\\{\\s*\"path\":\\s*\"([^\"]*)\",\\s*\"owner\":\\s*\"([^\"]*)\","
                    + "\\s*\"template\":\\s*\"([^\"]*)\",\\s*\"sha256\":\\s*\"([0-9a-f]*)\"\\s*}\\s*,?\\s*$");
    private static final Pattern SCHEMA_VERSION = Pattern.compile(
            "\"ownershipSchemaVersion\":\\s*(\\d+)");
    private static final Pattern GENERATOR_VERSION = Pattern.compile(
            "\"generatorVersion\":\\s*\"([^\"]*)\"");
    private static final Pattern TRANSACTION_POINTER = Pattern.compile(
            "\"lastSuccessfulTransaction\":\\s*\"([^\"]*)\"");
    private static final Pattern FILES_ARRAY = Pattern.compile(
            "\"files\":\\s*\\[(.*?)\\n\\s*]", Pattern.DOTALL);

    private final int schemaVersion;
    private final String generatorVersion;
    private final String lastSuccessfulTransaction;
    private final Map<String, OwnedFile> files;
    private final List<String> rejectedEntries;

    private ScaffoldOwnershipManifest(
            final int schemaVersion,
            final String generatorVersion,
            final String lastSuccessfulTransaction,
            final Map<String, OwnedFile> files,
            final List<String> rejectedEntries) {
        this.schemaVersion = schemaVersion;
        this.generatorVersion = generatorVersion;
        this.lastSuccessfulTransaction = lastSuccessfulTransaction;
        this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        this.rejectedEntries = List.copyOf(rejectedEntries);
    }

    /**
     * 从 manifest 文本解析 ownership 基线。
     *
     * <p>解析对未知格式采取保守策略：无法识别的文件记录不会成为可用基线，而是记入
     * {@link #rejectedEntries()}，由调用方决定是否要求显式迁移。绝不猜测 hash。</p>
     *
     * @param content manifest 全文。
     * @return 解析结果；{@link #isUsable()} 为 false 表示缺少当前 schema 可用基线。
     */
    public static ScaffoldOwnershipManifest parse(final String content) {
        String text = content == null ? "" : content;
        int version = integer(text, SCHEMA_VERSION).orElse(0);
        String generator = text(text, GENERATOR_VERSION);
        String transaction = text(text, TRANSACTION_POINTER);
        Map<String, OwnedFile> entries = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        Matcher block = FILES_ARRAY.matcher(text);
        if (block.find()) {
            for (String line : block.group(1).split("\\R")) {
                if (line.isBlank() || !line.contains("\"path\"")) {
                    continue;
                }
                Matcher entry = ENTRY.matcher(line);
                if (!entry.matches()) {
                    rejected.add(line.trim());
                    continue;
                }
                String path = entry.group(1);
                if (!isSafeRelative(path) || !validOwner(entry.group(2)) || entry.group(3).isBlank()
                        || (!path.equals(PATH) && !validHash(entry.group(4)))) {
                    rejected.add(line.trim());
                    continue;
                }
                if (entries.containsKey(path)) {
                    rejected.add("duplicate:" + path);
                    continue;
                }
                entries.put(path, new OwnedFile(path, entry.group(2), entry.group(3), entry.group(4)));
            }
        } else if (!text.isBlank()) {
            rejected.add("files-array-missing");
        }
        return new ScaffoldOwnershipManifest(version, generator, transaction, entries, rejected);
    }

    /**
     * 读取目标目录中的 manifest。
     *
     * @param target 工程根目录。
     * @return 解析结果；文件不存在时返回 {@link Optional#empty()}。
     * @throws IOException 读取失败。
     */
    public static Optional<ScaffoldOwnershipManifest> read(final Path target) throws IOException {
        Path manifest = target.resolve(PATH);
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        return Optional.of(parse(Files.readString(manifest, StandardCharsets.UTF_8)));
    }

    /**
     * 计算文件内容的 SHA-256 摘要。
     *
     * @param content 文本内容，按 UTF-8 编码。
     * @return 小写十六进制摘要。
     */
    public static String hashContent(final String content) {
        return toHex(digest().digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 计算磁盘文件的 SHA-256 摘要。
     *
     * @param file 目标文件。
     * @return 小写十六进制摘要。
     * @throws IOException 文件读取失败。
     */
    public static String hashFile(final Path file) throws IOException {
        return toHex(digest().digest(Files.readAllBytes(file)));
    }

    /** @return ownership schema 版本；0 表示未声明。 */
    public int schemaVersion() {
        return schemaVersion;
    }

    /** @return 生成器版本，可能为空字符串。 */
    public String generatorVersion() {
        return generatorVersion;
    }

    /** @return 最近一次成功事务 ID，可能为空字符串。 */
    public String lastSuccessfulTransaction() {
        return lastSuccessfulTransaction;
    }

    /** @return 不可变文件基线映射；顺序为 manifest 中出现顺序。 */
    public Map<String, OwnedFile> files() {
        return files;
    }

    /** @return 无法解析或不被信任的记录行；调用方必须据此拒绝猜测覆盖。 */
    public List<String> rejectedEntries() {
        return rejectedEntries;
    }

    /** @return 是否存在可用作三路比较的完整基线（当前 schema、无被拒记录、hash 非空）。 */
    public boolean isUsable() {
        if (schemaVersion < CURRENT_SCHEMA_VERSION || !rejectedEntries.isEmpty()) {
            return false;
        }
        return files.values().stream().allMatch(entry -> entry.hasHashBaseline());
    }

    /** @return 需要迁移的原因；为空表示基线可直接使用。 */
    public Optional<String> migrationReason() {
        if (files.isEmpty()) {
            return Optional.of("manifest 没有可用 ownership 记录");
        }
        if (schemaVersion == 0) {
            return Optional.of("manifest 缺少 ownershipSchemaVersion");
        }
        if (schemaVersion < CURRENT_SCHEMA_VERSION) {
            return Optional.of("ownershipSchemaVersion=" + schemaVersion
                    + " 低于当前 " + CURRENT_SCHEMA_VERSION);
        }
        if (!rejectedEntries.isEmpty()) {
            return Optional.of("存在 " + rejectedEntries.size() + " 条无法信任的 ownership 记录");
        }
        List<String> missing = files.values().stream()
                .filter(entry -> !entry.hasHashBaseline())
                .map(OwnedFile::path)
                .filter(path -> !path.equals(PATH))
                .toList();
        return missing.isEmpty()
                ? Optional.empty()
                : Optional.of("以下受控文件缺少基线 hash: " + String.join(", ", missing));
    }

    /**
     * 渲染 {@code files} 数组内容（不含外层中括号），按 path 稳定排序。
     *
     * @param entries 受控文件集合。
     * @return 每行一条记录、以逗号分隔的缩进文本。
     */
    public static String renderFilesArray(final List<OwnedFile> entries) {
        List<OwnedFile> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(OwnedFile::path));
        StringBuilder rendered = new StringBuilder();
        for (OwnedFile file : sorted) {
            if (rendered.length() > 0) {
                rendered.append(",\n");
            }
            rendered.append("    {\"path\": ").append(json(file.path()))
                    .append(", \"owner\": ").append(json(file.owner()))
                    .append(", \"template\": ").append(json(file.template()))
                    .append(", \"sha256\": ").append(json(file.sha256())).append('}');
        }
        return rendered.toString();
    }

    /**
     * 校验相对路径是否安全：拒绝绝对路径、盘符、空段与 {@code ..} 逃逸。
     *
     * @param relative 相对路径文本。
     * @return 可安全解析到目标根目录内时返回 true。
     */
    public static boolean isSafeRelative(final String relative) {
        if (relative == null || relative.isBlank()) {
            return false;
        }
        String normalized = relative.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")) {
            return false;
        }
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把相对路径解析到根目录，并拒绝任何逃逸。
     *
     * @param root 受控根目录。
     * @param relative 相对路径。
     * @return 规范化后的绝对路径。
     * @throws IllegalStateException 路径逃逸根目录。
     */
    public static Path resolveWithin(final Path root, final String relative) {
        if (!isSafeRelative(relative)) {
            throw new IllegalStateException("scaffold path escapes its root: " + relative);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalStateException("scaffold path escapes its root: " + relative);
        }
        return resolved;
    }

    private static boolean validOwner(final String owner) {
        return OwnedFile.OWNER_GENERATED.equals(owner) || OwnedFile.OWNER_USER.equals(owner);
    }

    private static boolean validHash(final String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}");
    }

    private static Optional<Integer> integer(final String text, final Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private static String text(final String value, final Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String toHex(final byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            result.append(String.format(Locale.ROOT, "%02x", value));
        }
        return result.toString();
    }

    /**
     * manifest 中的一条受控文件记录。
     *
     * @param path 相对路径。
     * @param owner 所有者：{@code generated} 或 {@code user}。
     * @param template 模板来源；synthetic 输出使用尖括号标记。
     * @param sha256 基线摘要；manifest 自身为空字符串。
     * @author zn
     */
    public record OwnedFile(String path, String owner, String template, String sha256) {

        /** generated 所有权值。 */
        public static final String OWNER_GENERATED = "generated";
        /** user 所有权值。 */
        public static final String OWNER_USER = "user";

        /** @return 是否具备可用于三路比较的 hash 基线。 */
        public boolean hasHashBaseline() {
            return !sha256.isEmpty() || path.equals(PATH);
        }

        /** @return 是否由生成器拥有。 */
        public boolean generated() {
            return OWNER_GENERATED.equals(owner);
        }
    }

    /**
     * 转义 JSON 字符串字面量。
     *
     * @param value 原始文本。
     * @return 带引号的 JSON 字面量。
     */
    static String json(final String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }
}
