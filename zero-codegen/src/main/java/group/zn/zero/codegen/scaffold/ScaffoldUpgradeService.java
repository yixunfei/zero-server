package group.zn.zero.codegen.scaffold;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 对已生成的脚手架执行 plan/apply/rollback/migrate。
 *
 * <p>模板渲染、三路比较和文件提交被刻意分开：{@link #plan} 只读目标工程并返回可审阅的分类结果，
 * {@link #apply} 只写入已被计划认可的内容。提交前会在
 * {@code .zero/scaffold/transactions/<id>} 保存完整前像（before）与目标快照（after），
 * 提交失败时事务目录与状态文件保留，供 {@link #rollback} 恢复。</p>
 *
 * <p>用户修改过的 generated 文件一律视为冲突，{@code --force} 不能绕过；未知文件从不进入事务。
 * 线程安全性：同一工程的 apply/rollback/abort 通过工程级独占锁串行化；锁元数据写入协议版本、进程和获取时间，锁冲突返回稳定错误码。</p>
 *
 * @author zn
 */
public final class ScaffoldUpgradeService {

    /** 受控事务目录（相对工程根）。 */
    private static final String TRANSACTIONS = ".zero/scaffold/transactions";
    /** 最近一次事务指针（相对工程根）。 */
    private static final String LATEST = ".zero/scaffold/LATEST";
    /** 事务锁文件（相对工程根）。 */
    private static final String LOCK = ".zero/scaffold/upgrade.lock";
    /** 锁协议版本。 */
    private static final String LOCK_PROTOCOL = "zero-scaffold-lock/v1";
    /** 事务状态文件名。 */
    private static final String STATE = "state";
    /** 事务前像目录名。 */
    private static final String BEFORE = "before";
    /** 事务目标目录名。 */
    private static final String AFTER = "after";
    /** 变更状态：生成器版本变化、内容需更新。 */
    private static final String STATUS_CHANGED = "generator-changed";
    /** 变更状态：目标内容与当前一致，无需写入。 */
    private static final String STATUS_UNCHANGED = "unchanged-target";
    /** 变更状态：用户已修改且目标不同，必须人工处理。 */
    private static final String STATUS_CONFLICT = "conflict";
    /** 受控文件在新模板中缺失。 */
    private static final String STATUS_MISSING = "missing";

    /** 文件替换实现；默认原子移动，测试可注入失败以验证恢复。 */
    @FunctionalInterface
    interface FileReplacer {
        /**
         * 用 source 替换 destination。
         *
         * @param source 已完成写入的临时文件。
         * @param destination 目标文件。
         * @throws IOException 替换失败。
         */
        void replace(Path source, Path destination) throws IOException;
    }

    private final FileReplacer replacer;

    /** 使用默认原子替换实现创建服务。 */
    public ScaffoldUpgradeService() {
        this(ScaffoldUpgradeService::atomicReplace);
    }

    /**
     * 使用自定义替换实现创建服务，供故障注入测试使用。
     *
     * @param replacer 文件替换实现。
     */
    ScaffoldUpgradeService(final FileReplacer replacer) {
        this.replacer = Objects.requireNonNull(replacer, "replacer");
    }

    /**
     * 计算升级计划；不写目标工程。
     *
     * @param request 生成请求；其输出目录应为已有工程。
     * @return 稳定排序的变更计划，包含渲染内容与冲突列表。
     * @throws IOException 读取目标或渲染模板失败。
     */
    public ScaffoldChangePlan plan(final ProjectScaffoldRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        Path target = request.outputDirectory();
        if (!Files.isDirectory(target) || !Files.isRegularFile(target.resolve(ScaffoldOwnershipManifest.PATH))) {
            return ScaffoldChangePlan.migrationRequired(
                    "zero-scaffold.json 不存在；请在新目录初始化，或先执行显式 migrate");
        }
        ScaffoldOwnershipManifest manifest = ScaffoldOwnershipManifest.read(target).orElseThrow();
        if (!manifest.isUsable()) {
            return ScaffoldChangePlan.migrationRequired(
                    manifest.migrationReason().orElse("ownership manifest 不可用"));
        }
        Path scratch = Files.createTempDirectory("zero-scaffold-plan-");
        try {
            Path rendered = scratch.resolve("rendered");
            new ProjectScaffoldGenerator(ScaffoldCatalog.standard().capabilityModel())
                    .generate(copyTo(request, rendered));
            return compare(target, rendered, manifest);
        } finally {
            deleteTree(scratch);
        }
    }

    /**
     * 应用无冲突计划，并保存可回滚快照。
     *
     * @param request 生成请求。
     * @return 已提交的事务摘要。
     * @throws IOException 快照或替换失败；失败时事务目录与恢复指针保留。
     */
    public ScaffoldTransaction apply(final ProjectScaffoldRequest request) throws IOException {
        Path target = request.outputDirectory();
        try (UpgradeLock ignored = acquireLock(target)) {
            ScaffoldChangePlan plan = plan(request);
            if (plan.requiresMigration()) {
                throw new ScaffoldUpgradeException("SCAFFOLD-MIGRATION-REQUIRED", plan.reason());
            }
            if (plan.hasConflicts()) {
                throw new ScaffoldUpgradeException("SCAFFOLD-CONFLICT",
                        "以下受控文件已被修改，需人工合并: " + String.join(", ", plan.conflicts()));
            }
            Path transaction = target.resolve(TRANSACTIONS).resolve(UUID.randomUUID().toString());
            Path before = transaction.resolve(BEFORE);
            Path after = transaction.resolve(AFTER);
            Files.createDirectories(before);
            Files.createDirectories(after);
            writeState(transaction, "PREPARED");
            List<String> paths = plan.writePaths();
            try {
                snapshot(target, before, paths);
                stage(after, plan, paths);
                writeState(transaction, "COMMITTING");
                commit(after, target, paths);
                writeState(transaction, "COMMITTED");
                atomicWriteString(target.resolve(LATEST), transaction.getFileName() + System.lineSeparator());
                return new ScaffoldTransaction(transaction.getFileName().toString(), transaction, "COMMITTED", paths);
            } catch (IOException | RuntimeException failure) {
                writeState(transaction, "RECOVERY_REQUIRED");
                throw failure;
            }
        }
    }

    /**
     * 回滚最近一次事务，仅恢复该事务声明的受控文件。
     *
     * @param target 工程根目录。
     * @return 回滚的事务 ID。
     * @throws IOException 指针或快照缺失、恢复失败。
     */
    public String rollback(final Path target) throws IOException {
        try (UpgradeLock ignored = acquireLock(target)) {
            return rollbackLocked(target);
        }
    }

    private String rollbackLocked(final Path target) throws IOException {
            Path transaction = locateLatest(target);
            Path before = transaction.resolve(BEFORE);
            Path after = transaction.resolve(AFTER);
            if (!Files.isDirectory(before) || !Files.isDirectory(after)) {
                throw new ScaffoldUpgradeException("SCAFFOLD-ROLLBACK-NOT-FOUND", "事务快照缺失: " + transaction);
            }
            String state = readState(transaction);
            if (state.equals("ROLLED_BACK")) {
                return transaction.getFileName().toString();
            }
            if (!state.equals("COMMITTED") && !state.equals("RECOVERY_REQUIRED")
                    && !state.equals("COMMITTING") && !state.equals("PREPARED")) {
                throw new ScaffoldUpgradeException("SCAFFOLD-ROLLBACK-INVALID", "事务状态不可回滚: " + state);
            }
            for (Path file : files(before)) {
                String relative = before.relativize(file).toString().replace('\\', '/');
                atomicReplace(file, ScaffoldOwnershipManifest.resolveWithin(target, relative));
            }
            for (Path file : files(after)) {
                String relative = after.relativize(file).toString().replace('\\', '/');
                Path restored = ScaffoldOwnershipManifest.resolveWithin(before, relative);
                Path destination = ScaffoldOwnershipManifest.resolveWithin(target, relative);
                if (!Files.exists(restored)) {
                    Files.deleteIfExists(destination);
                }
            }
            writeState(transaction, "ROLLED_BACK");
            return transaction.getFileName().toString();
        }

    /**
     * 放弃尚未成功提交的事务：仅允许对 {@code PREPARED}/{@code COMMITTING}/{@code RECOVERY_REQUIRED}
     * 状态执行，行为等同回滚但拒绝已提交的事务。
     *
     * @param target 工程根目录。
     * @return 被放弃的事务 ID。
     * @throws IOException 指针或快照缺失、恢复失败。
     */
    public String abort(final Path target) throws IOException {
        try (UpgradeLock ignored = acquireLock(target)) {
            Path transaction = locateLatest(target);
            String state = Files.isRegularFile(transaction.resolve(STATE))
                    ? Files.readString(transaction.resolve(STATE), StandardCharsets.UTF_8).trim() : "";
            if (state.equals("COMMITTED")) {
                throw new ScaffoldUpgradeException("SCAFFOLD-ABORT-NOT-ALLOWED",
                        "最近事务已提交，请使用 --rollback 显式回滚");
            }
            return rollbackLocked(target);
        }
    }

    /**
     * 显式迁移旧 manifest 的元数据；不覆盖任何生成文件。
     *
     * @param target 工程根目录。
     * @return 迁移后的 manifest 文本。
     * @throws IOException 读取或写回 manifest 失败。
     */
    public String migrate(final Path target) throws IOException {
        Path manifestPath = target.resolve(ScaffoldOwnershipManifest.PATH);
        if (!Files.isRegularFile(manifestPath)) {
            throw new ScaffoldUpgradeException("SCAFFOLD-MIGRATION-REQUIRED", "zero-scaffold.json 不存在");
        }
        ScaffoldOwnershipManifest manifest = ScaffoldOwnershipManifest.parse(
                Files.readString(manifestPath, StandardCharsets.UTF_8));
        if (manifest.files().isEmpty() || !manifest.rejectedEntries().isEmpty()) {
            throw new ScaffoldUpgradeException("SCAFFOLD-MIGRATION-INVALID",
                    "ownership 记录不可采用，需人工核对: " + manifest.rejectedEntries());
        }
        String marker = "{\n  \"schemaVersion\": " + manifest.schemaVersion()
                + ",\n  \"ownershipSchemaVersion\": " + ScaffoldOwnershipManifest.CURRENT_SCHEMA_VERSION
                + ",\n  \"generator\": \"zero-codegen/project-scaffold\",\n  \"files\": [\n"
                + ScaffoldOwnershipManifest.renderFilesArray(adopt(target, manifest)) + "\n  ]\n}\n";
        Path staged = Files.createTempFile(target, ".zero-manifest-", ".tmp");
        try {
            Files.writeString(staged, marker, StandardCharsets.UTF_8);
            atomicReplace(staged, manifestPath);
        } finally {
            Files.deleteIfExists(staged);
        }
        return marker;
    }

    private List<ScaffoldOwnershipManifest.OwnedFile> adopt(
            final Path target, final ScaffoldOwnershipManifest manifest) throws IOException {
        List<ScaffoldOwnershipManifest.OwnedFile> adopted = new ArrayList<>();
        for (ScaffoldOwnershipManifest.OwnedFile entry : manifest.files().values()) {
            String hash;
            try {
                hash = entry.path().equals(ScaffoldOwnershipManifest.PATH)
                        ? ScaffoldOwnershipManifest.SELF_HASH
                        : ScaffoldOwnershipManifest.hashFile(
                                ScaffoldOwnershipManifest.resolveWithin(target, entry.path()));
            } catch (IOException failure) {
                throw new ScaffoldUpgradeException("SCAFFOLD-MIGRATION-INVALID",
                        "ownership 文件不存在或不可读取: " + entry.path());
            }
            adopted.add(new ScaffoldOwnershipManifest.OwnedFile(
                    entry.path(), entry.owner(), entry.template(), hash));
        }
        return adopted;
    }

    private ScaffoldChangePlan compare(
            final Path target, final Path rendered, final ScaffoldOwnershipManifest manifest) throws IOException {
        Map<String, String> contents = new LinkedHashMap<>();
        List<ScaffoldChangePlan.Change> changes = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (ScaffoldOwnershipManifest.OwnedFile entry : manifest.files().values()) {
            String relative = entry.path();
            if (relative.equals(ScaffoldOwnershipManifest.PATH)) {
                continue;
            }
            Path generated = rendered.resolve(relative);
            if (!Files.isRegularFile(generated)) {
                changes.add(new ScaffoldChangePlan.Change(relative, STATUS_MISSING, entry.sha256(), ""));
                if (Files.isRegularFile(target.resolve(relative))) {
                    conflicts.add(relative);
                }
                continue;
            }
            String content = Files.readString(generated, StandardCharsets.UTF_8);
            String targetHash = ScaffoldOwnershipManifest.hashContent(content);
            Path current = target.resolve(relative);
            String currentHash = Files.isRegularFile(current) ? ScaffoldOwnershipManifest.hashFile(current) : "";
            if (currentHash.equals(entry.sha256())) {
                if (!currentHash.equals(targetHash)) {
                    contents.put(relative, content);
                    changes.add(new ScaffoldChangePlan.Change(relative, STATUS_CHANGED, entry.sha256(), targetHash));
                }
            } else if (currentHash.equals(targetHash)) {
                changes.add(new ScaffoldChangePlan.Change(relative, STATUS_UNCHANGED, entry.sha256(), targetHash));
            } else {
                conflicts.add(relative);
                changes.add(new ScaffoldChangePlan.Change(relative, STATUS_CONFLICT, entry.sha256(), targetHash));
            }
        }
        Path generatedManifest = rendered.resolve(ScaffoldOwnershipManifest.PATH);
        if (Files.isRegularFile(generatedManifest)) {
            contents.put(ScaffoldOwnershipManifest.PATH,
                    Files.readString(generatedManifest, StandardCharsets.UTF_8));
        }
        changes.sort(Comparator.comparing(ScaffoldChangePlan.Change::path));
        return new ScaffoldChangePlan(changes, conflicts, "", contents);
    }

    private void snapshot(final Path target, final Path before, final List<String> paths) throws IOException {
        for (String relative : paths) {
            Path current = ScaffoldOwnershipManifest.resolveWithin(target, relative);
            if (Files.isRegularFile(current)) {
                copy(current, ScaffoldOwnershipManifest.resolveWithin(before, relative));
            }
        }
    }

    private void stage(final Path after, final ScaffoldChangePlan plan, final List<String> paths) throws IOException {
        for (String relative : paths) {
            String content = plan.rendered().get(relative);
            if (content == null) {
                continue;
            }
            Path staged = ScaffoldOwnershipManifest.resolveWithin(after, relative);
            Files.createDirectories(staged.getParent());
            Files.writeString(staged, content, StandardCharsets.UTF_8);
        }
    }

    private void commit(final Path after, final Path target, final List<String> paths) throws IOException {
        for (String relative : paths) {
            Path staged = ScaffoldOwnershipManifest.resolveWithin(after, relative);
            if (Files.isRegularFile(staged)) {
                replacer.replace(staged, ScaffoldOwnershipManifest.resolveWithin(target, relative));
            }
        }
    }

    private Path locateLatest(final Path target) throws IOException {
        Path pointer = target.resolve(LATEST);
        if (!Files.isRegularFile(pointer)) {
            throw new ScaffoldUpgradeException("SCAFFOLD-ROLLBACK-NOT-FOUND", "没有可回滚的事务指针");
        }
        String id = Files.readString(pointer, StandardCharsets.UTF_8).trim();
        Path root = target.resolve(TRANSACTIONS).normalize();
        if (id.isEmpty()) {
            throw new ScaffoldUpgradeException("SCAFFOLD-ROLLBACK-INVALID", "事务指针为空");
        }
        Path transaction = root.resolve(id).normalize();
        if (!transaction.startsWith(root) || transaction.getParent() == null
                || !transaction.getParent().equals(root) || !Files.isDirectory(transaction)) {
            throw new ScaffoldUpgradeException("SCAFFOLD-ROLLBACK-INVALID", "事务指针无效: " + id);
        }
        return transaction;
    }

    private ProjectScaffoldRequest copyTo(final ProjectScaffoldRequest request, final Path output) {
        return new ProjectScaffoldRequest(request.projectName(), request.packageName(), output,
                request.zeroVersion(), request.template(), request.templateRoot(), request.selectedFromKeywords(),
                request.components(), false);
    }

    private static void atomicReplace(final Path source, final Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void atomicWriteString(final Path destination, final String value) throws IOException {
        Files.createDirectories(destination.getParent());
        Path staged = Files.createTempFile(destination.getParent(), ".zero-atomic-", ".tmp");
        try {
            Files.writeString(staged, value, StandardCharsets.UTF_8);
            atomicReplace(staged, destination);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static UpgradeLock acquireLock(final Path target) throws IOException {
        Path path = target.resolve(LOCK);
        Files.createDirectories(path.getParent());
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new ScaffoldUpgradeException("SCAFFOLD-UPGRADE-LOCKED", "工程正在被另一个升级事务使用: " + path);
            }
            String metadata = LOCK_PROTOCOL + "\nprocess=" + ProcessHandle.current().pid()
                    + "\nthread=" + Thread.currentThread().getName() + "\nacquiredAt=" + java.time.Instant.now() + "\n";
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(metadata.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
            return new UpgradeLock(channel, lock);
        } catch (OverlappingFileLockException | IOException failure) {
            channel.close();
            if (failure instanceof OverlappingFileLockException) {
                throw new ScaffoldUpgradeException("SCAFFOLD-UPGRADE-LOCKED", "当前 JVM 已持有工程升级锁: " + path);
            }
            throw failure;
        }
    }

    private record UpgradeLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    private static void copy(final Path source, final Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String readState(final Path transaction) throws IOException {
        Path state = transaction.resolve(STATE);
        return Files.isRegularFile(state) ? Files.readString(state, StandardCharsets.UTF_8).trim() : "";
    }

    private static void writeState(final Path transaction, final String value) throws IOException {
        atomicWriteString(transaction.resolve(STATE), value + System.lineSeparator());
    }

    private static List<Path> files(final Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList();
        }
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    /**
     * 一次升级事务的摘要。
     *
     * @param id 事务 ID。
     * @param directory 事务目录。
     * @param state 事务状态：{@code COMMITTED}/{@code ROLLED_BACK}/{@code RECOVERY_REQUIRED}。
     * @param paths 该事务涉及的受控相对路径，不可变且有序。
     * @author zn
     */
    public record ScaffoldTransaction(String id, Path directory, String state, List<String> paths) {
        public ScaffoldTransaction {
            paths = List.copyOf(paths);
        }
    }

    /**
     * 升级计划与冲突摘要。
     *
     * @param changes 稳定排序的单文件分类结果，不可变。
     * @param conflicts 需要人工处理的相对路径，不可变。
     * @param reason 需要迁移的原因；为空表示可执行 apply。
     * @param rendered 计划认可的最终内容（相对路径到文本），仅内存使用，不打印。
     * @author zn
     */
    public record ScaffoldChangePlan(
            List<Change> changes, List<String> conflicts, String reason, Map<String, String> rendered) {

        public ScaffoldChangePlan {
            changes = List.copyOf(changes);
            conflicts = List.copyOf(conflicts);
            rendered = Map.copyOf(rendered);
        }

        /** @return 是否存在用户修改冲突。 */
        public boolean hasConflicts() {
            return !conflicts.isEmpty();
        }

        /** @return 是否必须先执行显式迁移。 */
        public boolean requiresMigration() {
            return reason != null && !reason.isBlank();
        }

        /** @return apply 需要写入的受控路径；始终包含 manifest。 */
        public List<String> writePaths() {
            List<String> paths = new ArrayList<>();
            for (Change change : changes) {
            if (change.status().equals(STATUS_CHANGED)) {
                    paths.add(change.path());
                }
            }
            if (!paths.contains(ScaffoldOwnershipManifest.PATH)) {
                paths.add(ScaffoldOwnershipManifest.PATH);
            }
            return List.copyOf(paths);
        }

        /** @return 一行式摘要，供 CLI 与证据记录使用。 */
        public String summary() {
            long changed = changes.stream().filter(change -> change.status().equals(STATUS_CHANGED)).count();
            long conflicts = changes.stream().filter(change -> change.status().equals(STATUS_CONFLICT)).count();
            return "changes=" + changed + "|conflicts=" + conflicts;
        }

        static ScaffoldChangePlan migrationRequired(final String reason) {
            return new ScaffoldChangePlan(List.of(), List.of(), reason, Map.of());
        }

        /**
         * 单文件三路分类。
         *
         * @param path 相对路径。
         * @param status {@code generator-changed}/{@code unchanged-target}/{@code conflict}。
         * @param baseHash 上一个成功 manifest 的基线摘要。
         * @param targetHash 本次渲染内容的摘要。
         * @author zn
         */
        public record Change(String path, String status, String baseHash, String targetHash) {
        }
    }

    /** 可被 CLI 映射为稳定退出码的升级错误。 */
    public static final class ScaffoldUpgradeException extends IllegalStateException {

        /** 错误码，形如 {@code SCAFFOLD-CONFLICT}。 */
        private final String code;

        /**
         * 创建升级错误。
         *
         * @param code 稳定错误码。
         * @param message 修复建议。
         */
        public ScaffoldUpgradeException(final String code, final String message) {
            super(code + "|" + message);
            this.code = code;
        }

        /** @return 稳定错误码。 */
        public String code() {
            return code;
        }
    }
}
