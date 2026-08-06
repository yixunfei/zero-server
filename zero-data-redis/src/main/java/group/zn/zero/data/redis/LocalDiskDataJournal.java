package group.zn.zero.data.redis;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Redis 数据本地磁盘化追加日志。
 *
 * <p>该实现是首版同步 append-only zlog，用于 Redis 不可用或本地恢复测试。生产异步刷盘、
 * 分段轮转和 compaction 后续单独实现。
 *
 * @author zn
 */
public final class LocalDiskDataJournal {

    /**
     * 默认日志文件名。
     */
    private static final String DEFAULT_FILE_NAME = "data-00000001.zlog";

    /**
     * 根目录。
     */
    private final Path root;

    /**
     * 日志记录 codec。
     */
    private final RedisDataJournalEntryCodec codec;

    /**
     * 创建本地磁盘化追加日志。
     *
     * @param root 根目录；不可为空。
     * @throws NullPointerException 当根目录为空时抛出。
     */
    public LocalDiskDataJournal(final Path root) {
        this(root, new RedisDataJournalEntryCodec());
    }

    /**
     * 创建本地磁盘化追加日志。
     *
     * @param root 根目录；不可为空。
     * @param codec 日志记录 codec；不可为空。
     * @throws NullPointerException 当必要参数为空时抛出。
     */
    public LocalDiskDataJournal(final Path root, final RedisDataJournalEntryCodec codec) {
        this.root = Objects.requireNonNull(root, "root");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * 追加写入日志记录。
     *
     * @param entry 日志记录；不可为空。
     * @throws ZeroException 写入失败时抛出，绑定 `DataErrorCode.WRITE_FAILED`。
     */
    public synchronized void append(final RedisDataJournalEntry entry) {
        RedisDataJournalEntry current = Objects.requireNonNull(entry, "entry");
        try {
            Path path = journalPath(current.namespace(), current.collection());
            Files.createDirectories(path.getParent());
            byte[] entryBytes = codec.encode(current);
            ZeroWriter writer = new ZeroWriter(entryBytes.length + 5);
            writer.writeByteArray(entryBytes);
            Files.write(path, writer.toByteArray(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "append local data journal failed", ex);
        }
    }

    /**
     * 读取指定集合的全部日志记录。
     *
     * @param namespace 数据命名空间；不可为空。
     * @param collection 数据集合名称；不可为空。
     * @return 日志记录列表；不可为空；有序；可能为空；线程安全。
     * @throws ZeroException 读取失败时抛出，绑定 `DataErrorCode.READ_FAILED`。
     */
    public synchronized List<RedisDataJournalEntry> readAll(final String namespace, final String collection) {
        try {
            Path path = journalPath(namespace, collection);
            if (!Files.exists(path)) {
                return List.of();
            }
            byte[] bytes = Files.readAllBytes(path);
            ZeroReader reader = new ZeroReader(bytes);
            List<RedisDataJournalEntry> entries = new ArrayList<>();
            while (reader.isReadable()) {
                entries.add(codec.decode(reader.readByteArray()));
            }
            return List.copyOf(entries);
        } catch (IOException | RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "read local data journal failed", ex);
        }
    }

    /**
     * 返回指定集合的日志路径。
     *
     * @param namespace 数据命名空间；不可为空。
     * @param collection 数据集合名称；不可为空。
     * @return 日志路径；不可为空；线程安全。
     */
    public Path journalPath(final String namespace, final String collection) {
        return root.resolve(safeSegment(namespace, "namespace"))
                .resolve(safeSegment(collection, "collection"))
                .resolve(DEFAULT_FILE_NAME);
    }

    private String safeSegment(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()
                || current.contains("/")
                || current.contains("\\")
                || current.contains("..")) {
            throw new IllegalArgumentException(name + " is not a safe path segment");
        }
        return current;
    }
}
