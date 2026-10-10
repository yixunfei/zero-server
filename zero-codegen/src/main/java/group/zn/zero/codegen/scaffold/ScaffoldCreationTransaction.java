package group.zn.zero.codegen.scaffold;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 首建事务：完整 staging → 全目标预检 → journal → 创建 → 提交；失败仅撤销本次内容。 @author zn */
final class ScaffoldCreationTransaction {
    /** 首建独立恢复指针，不覆盖升级历史。 */
    static final String JOURNAL = ".zero/scaffold/create.json";
    /** 首建与升级共用锁文件，锁文件不能删除。 */
    private static final String LOCK = ".zero/scaffold/upgrade.lock";
    /** 故障注入，不暴露到业务 API。 */
    private final WriteHook hook;

    ScaffoldCreationTransaction() {
        this(index -> { });
    }

    ScaffoldCreationTransaction(final WriteHook hook) {
        this.hook = Objects.requireNonNull(hook, "hook");
    }

    void commit(final Path staged, final ProjectScaffoldRequest request) throws IOException {
        Path root = request.outputDirectory();
        validate(root);
        try (var lock = ScaffoldUpgradeService.acquireLock(root)) {
            if (Files.exists(root.resolve(JOURNAL))) {
                throw new IOException("SCAFFOLD-CREATE-RECOVERY-REQUIRED: use scaffold rollback before retry");
            }
            if (!request.force() && hasBusinessFiles(root)) {
                throw new IllegalStateException("outputDirectory already exists and is not empty; use --force");
            }
            List<CreatedFile> files = prepare(staged, root);
            if (files.isEmpty()) return;
            writeJournal(root, new Journal(1, "PREPARED", files));
            try {
                for (int index = 0; index < files.size(); index++) {
                    hook.beforeWrite(index);
                    CreatedFile file = files.get(index);
                    Path target = resolve(root, file.path());
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("output appeared: " + target);
                    ScaffoldUpgradeService.atomicWriteString(target, file.content());
                }
                writeJournal(root, new Journal(1, "COMMITTED", files));
            } catch (IOException | RuntimeException failure) {
                try {
                    restore(root, files);
                    Files.delete(root.resolve(JOURNAL));
                } catch (IOException recovery) {
                    failure.addSuppressed(recovery);
                }
                throw failure;
            }
            try {
                Files.delete(root.resolve(JOURNAL));
            } catch (IOException failure) {
                throw new IOException("SCAFFOLD-CREATE-COMMITTED: cleanup failed; use scaffold rollback to finish cleanup", failure);
            }
        }
    }

    /** 调用方持有共用锁；返回是否处理首建日志。 */
    static boolean recoverLocked(final Path root) throws IOException {
        Path journal = root.resolve(JOURNAL);
        validate(journal);
        if (!Files.exists(journal)) return false;
        Journal record;
        try {
            record = new Gson().fromJson(Files.readString(journal), Journal.class);
            if (record == null || record.schema() != 1 || record.files() == null
                    || !("PREPARED".equals(record.phase()) || "COMMITTED".equals(record.phase()))) {
                throw new IllegalArgumentException("invalid creation journal");
            }
        } catch (RuntimeException failure) {
            throw new IOException("invalid scaffold creation journal; preserve and inspect", failure);
        }
        Set<Path> unique = new HashSet<>();
        for (CreatedFile file : record.files()) {
            if (file == null || file.content() == null || !unique.add(resolve(root, file.path()))) {
                throw new IOException("invalid creation journal entry");
            }
        }
        if (record.phase().equals("COMMITTED")) {
            for (CreatedFile file : record.files()) requireContent(root, file, false);
        } else {
            restore(root, record.files());
        }
        Files.delete(journal);
        return true;
    }

    private static List<CreatedFile> prepare(final Path staged, final Path root) throws IOException {
        List<CreatedFile> result = new ArrayList<>();
        try (var paths = Files.walk(staged)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = staged.relativize(file).toString().replace('\\', '/');
                Path target = resolve(root, relative);
                String content = Files.readString(file);
                if (Files.exists(target)) {
                    if (!Files.isRegularFile(target) || !Files.readString(target).equals(content)) {
                        throw new IllegalStateException("scaffold output conflict; refusing to overwrite modified file: " + relative);
                    }
                } else {
                    result.add(new CreatedFile(relative, content));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void restore(final Path root, final List<CreatedFile> files) throws IOException {
        for (CreatedFile file : files) requireContent(root, file, true);
        for (int index = files.size() - 1; index >= 0; index--) {
            CreatedFile file = files.get(index);
            requireContent(root, file, true);
            Files.deleteIfExists(resolve(root, file.path()));
        }
    }

    private static void requireContent(final Path root, final CreatedFile file, final boolean missingAllowed) throws IOException {
        Path target = resolve(root, file.path());
        if (missingAllowed && !Files.exists(target)) return;
        if (!Files.isRegularFile(target) || !Files.readString(target).equals(file.content())) {
            throw new IOException("scaffold output changed outside creation; preserve and inspect: " + target);
        }
    }

    private static Path resolve(final Path root, final String relative) throws IOException {
        if (relative == null || relative.startsWith(".zero/")) throw new IOException("invalid scaffold creation path");
        Path target;
        try {
            target = ScaffoldOwnershipManifest.resolveWithin(root, relative);
        } catch (RuntimeException failure) {
            throw new IOException("invalid scaffold creation path", failure);
        }
        if (target.startsWith(root.resolve(".zero"))) throw new IOException("creation cannot write transaction metadata");
        validate(target);
        return target;
    }

    static void validate(final Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("scaffold path must not contain symbolic links: " + current);
        }
    }

    private static boolean hasBusinessFiles(final Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path -> !path.equals(root) && !Files.isDirectory(path)
                    && !root.relativize(path).toString().replace('\\', '/').equals(LOCK));
        }
    }

    private static void writeJournal(final Path root, final Journal journal) throws IOException {
        ScaffoldUpgradeService.atomicWriteString(root.resolve(JOURNAL), new Gson().toJson(journal));
    }

    /** 待创建文件；既有相同文件不进入事务。 */
    private record CreatedFile(String path, String content) { }
    /** 首建前像均不存在，提交状态决定撤销还是清理。 */
    private record Journal(int schema, String phase, List<CreatedFile> files) { }
    /** 测试提交故障入口。 */
    @FunctionalInterface interface WriteHook {
        /** 写第 index 个文件前执行，可注入失败。 */
        void beforeWrite(int index) throws IOException;
    }
}
