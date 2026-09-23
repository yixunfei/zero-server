package group.zn.zero.data.redis;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.protocol.buffer.ZeroWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;

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

    /** 当前实例已验证的完整边界，避免每次追加重新扫描；每个根目录须由一个实例独占写入。 */
    private final Map<Path, Long> verifiedLengths = new HashMap<>();

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
            try (FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.READ)) {
                long boundary = recoverBoundary(path, channel.size());
                channel.truncate(boundary);
                channel.position(boundary);
                byte[] bytes = writer.toByteArray();
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
                verifiedLengths.put(path, channel.position());
            }
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
            List<RedisDataJournalEntry> entries = new ArrayList<>();
            int offset = 0;
            while (offset < bytes.length) {
                int[] frame = completeFrame(bytes, offset);
                if (frame == null) {
                    break;
                }
                entries.add(codec.decode(java.util.Arrays.copyOfRange(
                        bytes, frame[0], frame[0] + frame[1])));
                offset = frame[0] + frame[1];
            }
            return List.copyOf(entries);
        } catch (IOException | RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.READ_FAILED, "read local data journal failed", ex);
        }
    }

    /** 首次追加或上次写失败后恢复完整帧边界，完整帧损坏不可静默截断。 */
    private long recoverBoundary(final Path path, final long size) throws IOException {
        Long verified = verifiedLengths.get(path);
        if (verified != null && verified == size) {
            return size;
        }
        byte[] bytes = Files.readAllBytes(path);
        int offset = 0;
        while (offset < bytes.length) {
            int[] frame = completeFrame(bytes, offset);
            if (frame == null) {
                break;
            }
            codec.decode(java.util.Arrays.copyOfRange(bytes, frame[0], frame[0] + frame[1]));
            offset = frame[0] + frame[1];
        }
        return offset;
    }

    /**
     * 解析一个完整的长度前缀帧；尾部不完整帧返回 null。
     */
    private int[] completeFrame(final byte[] bytes, final int start) {
        int cursor = start;
        int length = 0;
        int shift = 0;
        for (int count = 0; count < 5; count++) {
            if (cursor >= bytes.length) {
                return null;
            }
            int value = bytes[cursor++] & 0xFF;
            length |= (value & 0x7F) << shift;
            if ((value & 0x80) == 0) {
                if (count == 4 && (value & 0xF8) != 0) {
                    throw ZeroException.of(DataErrorCode.READ_FAILED,
                            "local data journal frame length overflow", null);
                }
                if (length > bytes.length - cursor) {
                    return null;
                }
                return new int[] {cursor, length};
            }
            shift += 7;
        }
        throw ZeroException.of(DataErrorCode.READ_FAILED,
                "local data journal frame length is too long", null);
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
