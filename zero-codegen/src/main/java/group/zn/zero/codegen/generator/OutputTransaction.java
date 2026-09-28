package group.zn.zero.codegen.generator;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** 可恢复输出事务：加锁 → 保存前后镜像 → 替换 → 清理；异常时回滚，进程退出后显式恢复。 */
final class OutputTransaction {
    /** 未完成事务的恢复日志，不依赖外部临时目录。 */
    static final String JOURNAL = ".zero-codegen/pending.json";
    /** 当前工程元数据根目录。 */
    private final Path root;
    /** 当前配置授权写入的路径边界。 */
    private final Predicate<Path> allowed;

    OutputTransaction(final Path root, final Predicate<Path> allowed) {
        this.root = root;
        this.allowed = allowed;
    }

    /** 执行一批已准备的变更，锁期间再检查磁盘内容。 */
    void apply(final List<Mutation> mutations) throws IOException {
        apply(mutations, index -> { });
    }

    /** 支持故障注入的提交入口，用于验证部分落盘后的恢复。 */
    void apply(final List<Mutation> mutations, final CommitHook hook) throws IOException {
        if (mutations.isEmpty()) {
            return;
        }
        try (Locks locks = lock(mutations)) {
            Path journal = root.resolve(JOURNAL);
            if (Files.exists(journal)) {
                throw new IOException("pending codegen transaction; run --mode recover: " + journal);
            }
            verify(mutations, false);
            OutputFiles.replace(journal, new Gson().toJson(new Journal(1, mutations)));
            try {
                for (int index = 0; index < mutations.size(); index++) {
                    hook.beforeWrite(index);
                    Mutation mutation = mutations.get(index);
                    verify(List.of(mutation), false);
                    OutputFiles.replace(Path.of(mutation.path()), mutation.after());
                }
            } catch (IOException | RuntimeException ex) {
                try {
                    restore(mutations);
                    Files.delete(journal);
                } catch (IOException recovery) {
                    ex.addSuppressed(recovery);
                }
                throw ex;
            }
            Files.delete(journal);
        }
    }

    /** 回滚遗留事务；遇到事务之外的编辑时保留日志并停止。 */
    void recover() throws IOException {
        Path journal = root.resolve(JOURNAL);
        String text = OutputFiles.read(journal);
        if (text == null) {
            return;
        }
        Journal pending;
        try {
            pending = new Gson().fromJson(text, Journal.class);
            if (pending == null || pending.schema() != 1 || pending.mutations() == null) {
                throw new IllegalArgumentException("invalid journal");
            }
        } catch (RuntimeException ex) {
            throw new IOException("invalid recovery journal: " + journal, ex);
        }
        try (Locks locks = lock(pending.mutations())) {
            if (!text.equals(OutputFiles.read(journal))) {
                throw new IOException("recovery journal changed during inspection");
            }
            restore(pending.mutations());
            Files.delete(journal);
        }
    }

    private void restore(final List<Mutation> mutations) throws IOException {
        verify(mutations, true);
        for (int index = mutations.size() - 1; index >= 0; index--) {
            Mutation mutation = mutations.get(index);
            Path path = Path.of(mutation.path());
            String current = OutputFiles.read(path);
            if (!Objects.equals(current, mutation.before())) {
                if (!Objects.equals(current, mutation.after())) {
                    throw new IOException("output changed during recovery; preserve and inspect: " + path);
                }
                OutputFiles.replace(path, mutation.before());
            }
        }
    }

    private void verify(final List<Mutation> mutations, final boolean recovering) throws IOException {
        for (Mutation mutation : mutations) {
            Path path = checkedPath(mutation);
            String current = OutputFiles.read(path);
            if (!Objects.equals(current, mutation.before())
                    && !(recovering && Objects.equals(current, mutation.after()))) {
                throw new IOException("output changed outside this transaction; preserve and inspect: " + path);
            }
        }
    }

    private Path checkedPath(final Mutation mutation) throws IOException {
        if (mutation == null || mutation.path() == null) {
            throw new IOException("invalid transaction path");
        }
        Path path = Path.of(mutation.path()).toAbsolutePath().normalize();
        if (!allowed.test(path) || path.equals(root.resolve(JOURNAL)) || path.getFileName().toString().equals(".zero-codegen.lock")) {
            throw new IOException("transaction path outside configured outputs: " + path);
        }
        OutputFiles.validate(path);
        return path;
    }

    private Locks lock(final List<Mutation> mutations) throws IOException {
        List<Path> directories = new ArrayList<>();
        directories.add(root.resolve(".zero-codegen"));
        for (Mutation mutation : mutations) {
            directories.add(checkedPath(mutation).getParent());
        }
        Locks locks = new Locks();
        try {
            for (Path directory : directories.stream().distinct().sorted().toList()) {
                Files.createDirectories(directory);
                Path lockPath = directory.resolve(".zero-codegen.lock");
                OutputFiles.validate(lockPath);
                FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                locks.channels.add(channel);
                FileLock lock;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException ex) {
                    throw new IOException("another codegen operation owns " + directory, ex);
                }
                if (lock == null) {
                    throw new IOException("another codegen process owns " + directory);
                }
                locks.locks.add(lock);
            }
            return locks;
        } catch (IOException | RuntimeException ex) {
            locks.close();
            throw ex;
        }
    }

    /** 变更前后镜像；null 代表不存在。 */
    record Mutation(String path, String before, String after) {
    }

    /** 有版本的恢复日志。 */
    private record Journal(int schema, List<Mutation> mutations) {
    }

    /** 可注入 IO 失败，不对业务使用方暴露。 */
    @FunctionalInterface
    interface CommitHook {
        /** 写入前回调，可抛出模拟 IO 失败。 */
        void beforeWrite(int index) throws IOException;
    }

    /** 锁通道在所有退出路径释放；锁文件保留，避免删除后不同 inode 绕过锁。 */
    private static final class Locks implements AutoCloseable {
        /** 已打开的锁通道。 */
        private final List<FileChannel> channels = new ArrayList<>();
        /** 持有的系统文件锁。 */
        private final List<FileLock> locks = new ArrayList<>();

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (FileChannel channel : channels) {
                try {
                    channel.close();
                } catch (IOException ex) {
                    if (failure == null) {
                        failure = ex;
                    } else {
                        failure.addSuppressed(ex);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
