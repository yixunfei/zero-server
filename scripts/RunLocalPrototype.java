import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * zeroServer 本地原型一命令生成并 smoke 工具。
 *
 * <p>该工具显式串联当前仓库已有的本地能力：先可选安装当前 SNAPSHOT，再调用
 * {@code NewLocalGame} 生成 local/prototype 项目，最后调用 {@code RunLocalScaffold}
 * 执行结构检查、Maven test 和 Maven run。它不创建正式模块，不连接外部中间件，也不证明生产就绪。</p>
 *
 * @author zn
 */
public final class RunLocalPrototype {

    /**
     * 最低 Java feature 版本。
     */
    private static final int MINIMUM_JAVA_FEATURE = 21;

    /**
     * 最低 Maven 主版本。
     */
    private static final int MINIMUM_MAVEN_MAJOR = 3;

    /**
     * 最低 Maven 次版本。
     */
    private static final int MINIMUM_MAVEN_MINOR = 9;

    /**
     * 默认项目名。
     */
    private static final String DEFAULT_PROJECT_NAME = "zero-local-prototype";

    /**
     * 默认包名。
     */
    private static final String DEFAULT_PACKAGE_NAME = "group.zn.zero.localprototype";

    /**
     * 默认 zeroServer 版本。
     */
    private static final String DEFAULT_ZERO_VERSION = "0.1.0-SNAPSHOT";

    /**
     * 默认模板。
     */
    private static final String DEFAULT_TEMPLATE = "local";

    /**
     * manifest 文件名。
     */
    private static final String MANIFEST_FILE = "zero-scaffold.json";

    /**
     * JSON 字符串字段匹配器。
     */
    private static final Pattern STRING_FIELD_PATTERN = Pattern.compile(
            "\"([A-Za-z0-9_]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    /**
     * Maven 版本匹配器。
     */
    private static final Pattern MAVEN_VERSION_PATTERN = Pattern.compile(
            "Apache Maven\\s+([0-9]+(?:\\.[0-9]+){1,3})");

    private RunLocalPrototype() {
    }

    /**
     * 运行本地原型生成和 smoke。
     *
     * @param args 命令行参数。
     * @throws IOException 子进程启动或 manifest 读取失败时抛出。
     * @throws InterruptedException 当前线程等待子进程时被中断。
     */
    public static void main(final String[] args) throws IOException, InterruptedException {
        if (hasFlag(args, "--help", "-h")) {
            printHelp();
            return;
        }
        Options options = Options.parse(args);
        verifyEnvironment();
        Path projectDir = options.outputDir().toAbsolutePath().normalize();

        System.out.println("zeroServer local prototype runner");
        System.out.println("  projectDir: " + projectDir);
        System.out.println("  projectName: " + options.projectName());
        System.out.println("  packageName: " + options.packageName());
        System.out.println("  selector: " + options.selectorLabel());
        System.out.println("  zeroVersion: " + options.zeroVersion());
        System.out.println();

        boolean installed = false;
        if (!options.skipInstall()) {
            runCommand(List.of(mavenCommand(), "-q", "-DskipTests", "install"));
            installed = true;
        }

        runCommand(generateCommand(options, projectDir));
        runCommand(smokeCommand(options, projectDir));

        Manifest manifest = Manifest.read(projectDir.resolve(MANIFEST_FILE));
        Path businessGuide = projectDir.resolve("BUSINESS_GUIDE.md");
        System.out.println();
        System.out.println("Next business step:");
        System.out.println("  read \"" + businessGuide + "\"");
        System.out.println("  follow the `First Business Change` recipe before adding broader systems");
        System.out.println("  keep this generated project local/prototype until formal promotion is confirmed");
        System.out.println();
        System.out.println("zero-local-prototype-runner=ok"
                + "|template=" + manifest.template()
                + "|project=" + manifest.projectName()
                + "|installed=" + installed
                + "|tested=" + !options.skipTests()
                + "|ran=" + !options.skipRun()
                + "|externalMiddleware=false"
                + "|productionReady=false"
                + "|requiresConfirmation=true");
    }

    private static void verifyEnvironment() throws IOException, InterruptedException {
        int javaFeature = Runtime.version().feature();
        if (javaFeature < MINIMUM_JAVA_FEATURE) {
            throw new IllegalStateException("Java " + MINIMUM_JAVA_FEATURE
                    + "+ is required, current feature version is " + javaFeature);
        }
        if (!isRepositoryRoot(Path.of("").toAbsolutePath().normalize())) {
            throw new IllegalStateException("Run this command from the zeroServer repository root.");
        }
        CommandResult result;
        try {
            result = runCommand(List.of(mavenCommand(), "--version"));
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Maven 3.9+ is required and mvn must be available on PATH.", ex);
        }
        String mavenVersion = parseMavenVersion(result.output());
        if (mavenVersion.isBlank()) {
            throw new IllegalStateException("Maven is callable, but its version could not be parsed.");
        }
        if (!isMavenVersionSupported(mavenVersion)) {
            throw new IllegalStateException("Maven 3.9+ is required, current version is " + mavenVersion);
        }
    }

    private static boolean isRepositoryRoot(final Path root) {
        return Files.isRegularFile(root.resolve("pom.xml"))
                && Files.isRegularFile(root.resolve("README.md"))
                && Files.isRegularFile(root.resolve("zero-parent").resolve("pom.xml"))
                && Files.isDirectory(root.resolve("templates"));
    }

    private static String parseMavenVersion(final String output) {
        Matcher matcher = MAVEN_VERSION_PATTERN.matcher(output);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean isMavenVersionSupported(final String version) {
        String[] parts = version.split("\\.");
        int major = parseInt(parts, 0);
        int minor = parseInt(parts, 1);
        return major > MINIMUM_MAVEN_MAJOR
                || major == MINIMUM_MAVEN_MAJOR && minor >= MINIMUM_MAVEN_MINOR;
    }

    private static int parseInt(final String[] parts, final int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static List<String> generateCommand(final Options options, final Path projectDir) {
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.add("scripts/NewLocalGame.java");
        if (options.fromKeywords().isBlank()) {
            command.add("--template");
            command.add(options.template());
        } else {
            command.add("--fromKeywords");
            command.add(options.fromKeywords());
        }
        command.add("--projectName");
        command.add(options.projectName());
        command.add("--packageName");
        command.add(options.packageName());
        command.add("--outputDir");
        command.add(projectDir.toString());
        command.add("--zeroVersion");
        command.add(options.zeroVersion());
        if (options.force()) {
            command.add("--force");
        }
        return command;
    }

    private static List<String> smokeCommand(final Options options, final Path projectDir) {
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.add("scripts/RunLocalScaffold.java");
        command.add("--projectDir");
        command.add(projectDir.toString());
        if (options.skipTests()) {
            command.add("--skipTests");
        }
        if (options.skipRun()) {
            command.add("--skipRun");
        }
        return command;
    }

    private static CommandResult runCommand(final List<String> command) throws IOException, InterruptedException {
        System.out.println("$ " + String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = process.inputReader(processOutputCharset())) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
                System.out.println(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed with exit code " + exitCode
                    + System.lineSeparator() + String.join(" ", command)
                    + System.lineSeparator() + tail(output.toString()));
        }
        return new CommandResult(exitCode, output.toString());
    }

    private static String javaCommand() {
        String binary = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", binary).toString();
    }

    private static String mavenCommand() {
        return isWindows() ? "mvn.cmd" : "mvn";
    }

    private static Charset processOutputCharset() {
        String name = System.getProperty("stdout.encoding", System.getProperty("native.encoding", ""));
        if (name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException ex) {
            return StandardCharsets.UTF_8;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String tail(final String value) {
        int maxLength = 4_000;
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(value.length() - maxLength);
    }

    private static boolean hasFlag(final String[] args, final String... flags) {
        for (String arg : args) {
            for (String flag : flags) {
                if (flag.equalsIgnoreCase(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, String> parseStringFields(final String text) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = STRING_FIELD_PATTERN.matcher(text);
        while (matcher.find()) {
            result.put(matcher.group(1), unescapeJsonString(matcher.group(2)));
        }
        return result;
    }

    private static String unescapeJsonString(final String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                builder.append(current);
                continue;
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                case '/' -> builder.append('/');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                case 'u' -> {
                    if (index + 4 >= value.length()) {
                        builder.append("\\u");
                    } else {
                        String hex = value.substring(index + 1, index + 5);
                        try {
                            builder.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        } catch (NumberFormatException ex) {
                            builder.append("\\u").append(hex);
                            index += 4;
                        }
                    }
                }
                default -> builder.append(escaped);
            }
        }
        return builder.toString();
    }

    private static void printHelp() {
        System.out.println("zeroServer local prototype runner");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java scripts/RunLocalPrototype.java --fromKeywords \"open world shard\" "
                + "--projectName my-world-game --packageName group.zn.zero.generated.myworld "
                + "--outputDir target/my-world-game --force");
        System.out.println("  java scripts/RunLocalPrototype.java --template room "
                + "--projectName my-room-game --packageName group.zn.zero.generated.myroom "
                + "--outputDir target/my-room-game");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --fromKeywords <words>    Select scaffold template by business keywords.");
        System.out.println("  --template <template>     Scaffold template. Default: " + DEFAULT_TEMPLATE);
        System.out.println("  --projectName <name>      Project artifact name. Default: " + DEFAULT_PROJECT_NAME);
        System.out.println("  --packageName <package>   Java package. Default: " + DEFAULT_PACKAGE_NAME);
        System.out.println("  --outputDir <path>        Output directory. Default: target/generated/<projectName>.");
        System.out.println("  --zeroVersion <version>   zeroServer dependency version. Default: " + DEFAULT_ZERO_VERSION);
        System.out.println("  --force                   Overwrite known scaffold files in outputDir.");
        System.out.println("  --skipInstall             Skip root `mvn -q -DskipTests install`.");
        System.out.println("  --skipTests               Skip generated project Maven clean test.");
        System.out.println("  --skipRun                 Skip generated project Maven exec:java.");
        System.out.println("  --help, -h                Print this help.");
        System.out.println();
        System.out.println("On success, read generated BUSINESS_GUIDE.md and follow `First Business Change`.");
        System.out.println("This tool writes a local/prototype scaffold and may run Maven. It is not production readiness.");
    }

    /**
     * 命令行参数。
     *
     * @param projectName 项目名称。
     * @param packageName Java 包名。
     * @param outputDir 输出目录。
     * @param zeroVersion zeroServer 版本。
     * @param template 模板名。
     * @param fromKeywords 业务关键词。
     * @param force 是否覆盖已知脚手架文件。
     * @param skipInstall 是否跳过根仓库 install。
     * @param skipTests 是否跳过生成项目测试。
     * @param skipRun 是否跳过生成项目运行。
     */
    private record Options(
            String projectName,
            String packageName,
            Path outputDir,
            String zeroVersion,
            String template,
            String fromKeywords,
            boolean force,
            boolean skipInstall,
            boolean skipTests,
            boolean skipRun) {

        private static Options parse(final String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean force = false;
            boolean skipInstall = false;
            boolean skipTests = false;
            boolean skipRun = false;
            for (int index = 0; index < args.length; index++) {
                String key = args[index];
                if ("--force".equalsIgnoreCase(key)) {
                    force = true;
                    continue;
                }
                if ("--skipInstall".equalsIgnoreCase(key) || "--skip-install".equalsIgnoreCase(key)) {
                    skipInstall = true;
                    continue;
                }
                if ("--skipTests".equalsIgnoreCase(key) || "--skip-tests".equalsIgnoreCase(key)) {
                    skipTests = true;
                    continue;
                }
                if ("--skipRun".equalsIgnoreCase(key) || "--skip-run".equalsIgnoreCase(key)) {
                    skipRun = true;
                    continue;
                }
                if (!key.startsWith("--")) {
                    throw new IllegalArgumentException("unsupported argument: " + key);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("missing value for argument: " + key);
                }
                values.put(key.toLowerCase(Locale.ROOT), args[++index]);
            }
            String projectName = values.getOrDefault("--projectname", DEFAULT_PROJECT_NAME);
            String packageName = values.getOrDefault("--packagename", DEFAULT_PACKAGE_NAME);
            String output = values.getOrDefault("--outputdir",
                    Path.of("target", "generated", projectName).toString());
            String zeroVersion = values.getOrDefault("--zeroversion", DEFAULT_ZERO_VERSION);
            String fromKeywords = values.getOrDefault("--fromkeywords",
                    values.getOrDefault("--from-keywords", ""));
            if (!fromKeywords.isBlank() && values.containsKey("--template")) {
                throw new IllegalArgumentException("--template and --fromKeywords cannot be used together");
            }
            String template = fromKeywords.isBlank() ? values.getOrDefault("--template", DEFAULT_TEMPLATE) : "";
            validate(projectName, packageName, zeroVersion);
            return new Options(projectName, packageName, Path.of(output), zeroVersion, template, fromKeywords, force,
                    skipInstall, skipTests, skipRun);
        }

        private String selectorLabel() {
            if (!fromKeywords.isBlank()) {
                return "fromKeywords=" + fromKeywords;
            }
            return "template=" + template;
        }
    }

    private static void validate(final String projectName, final String packageName, final String zeroVersion) {
        if (projectName.isBlank()) {
            throw new IllegalArgumentException("projectName must not be blank");
        }
        if (!projectName.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("projectName contains unsupported characters");
        }
        if (!packageName.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("packageName must be a valid dotted Java package");
        }
        if (zeroVersion.isBlank()) {
            throw new IllegalArgumentException("zeroVersion must not be blank");
        }
    }

    /**
     * 脚手架 manifest 摘要。
     *
     * @param projectName 项目名。
     * @param template 模板名。
     */
    private record Manifest(String projectName, String template) {

        private static Manifest read(final Path manifest) throws IOException {
            if (!Files.isRegularFile(manifest)) {
                throw new IllegalStateException("zero-scaffold.json not found: " + manifest);
            }
            Map<String, String> fields = parseStringFields(Files.readString(manifest, StandardCharsets.UTF_8));
            String projectName = required(fields, "projectName", manifest);
            String template = required(fields, "template", manifest);
            return new Manifest(projectName, template);
        }

        private static String required(final Map<String, String> fields, final String name, final Path manifest) {
            String value = fields.getOrDefault(name, "");
            if (value.isBlank()) {
                throw new IllegalStateException(manifest + " missing required string field: " + name);
            }
            return value;
        }
    }

    /**
     * 子进程执行结果。
     *
     * @param exitCode 退出码。
     * @param output 合并后的输出。
     */
    private record CommandResult(int exitCode, String output) {
    }
}
