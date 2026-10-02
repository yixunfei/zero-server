package group.zn.zero.data.persistence.durable;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCodec;
import group.zn.zero.data.error.DataErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * 单进程独占的有界耐久意图日志：完整帧 + CRC32C -> force -> 确认。
 * 尾部不完整帧可截断；完整帧损坏拒绝启动。只保证本地磁盘保留时的 JVM 崩溃恢复。
 * 方法线程安全且执行同步磁盘 IO，必须由受管 IO 执行域调用。
 * @author zn
 */
public final class LocalDurableJournal implements AutoCloseable {
    /** 版本化文件头；此格式不与 Redis 镜像日志混用。 */
    private static final long MAGIC = 0x5a44555200000001L;
    /** 单条帧预算，防止损坏长度引发巨量分配。 */
    private static final int MAX_FRAME = 16 * 1024 * 1024;
    /** 日志文件及独占锁的资源。 */
    private final Path path;
    /** 操作总数预算；包括已完成的幂等收据，满额拒绝而非静默遗忘。 */
    private final int maxOperations;
    /** 文件字节预算。 */
    private final long maxBytes;
    /** 保留完整最新状态，提交顺序稳定；只在本实例锁内访问。 */
    private final Map<String, DurableRecord> records = new LinkedHashMap<>();
    /** 只索引未解决实体，避免每次提交扫描全部已完成收据。 */
    private final Map<EntityKey, String> unresolved = new LinkedHashMap<>();
    /** 文件所有权通道。 */
    private final FileChannel lockChannel;
    /** OS 进程锁；进程崩溃由 OS 释放。 */
    private final FileLock lock;
    /** 写入错误后禁止继续追加，必须重新打开并恢复边界。 */
    private boolean poisoned;
    /** 关闭标记。 */
    private boolean closed;

    /** 打开或恢复日志；同步 IO，冷启动调用。拒绝目录并发打开和超预算文件。
     * @param directory 独占目录。 @param maxBytes 最大日志字节数。 @param maxOperations 最大操作数。 */
    public LocalDurableJournal(final Path directory, final long maxBytes, final int maxOperations) {
        if (maxBytes < 1024 || maxOperations < 1) throw new IllegalArgumentException("invalid journal budget");
        this.maxBytes = maxBytes;
        this.maxOperations = maxOperations;
        FileChannel acquired = null;
        FileLock ownership = null;
        try {
            Files.createDirectories(directory);
            Path root = directory.toRealPath();
            path = root.resolve("intents.zwal");
            acquired = FileChannel.open(root.resolve("journal.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            ownership = acquired.tryLock();
            if (ownership == null) throw new IOException("journal already in use");
            recover();
            lockChannel = acquired;
            lock = ownership;
        } catch (IOException | RuntimeException failure) {
            try { if (ownership != null) ownership.release(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            try { if (acquired != null) acquired.close(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw error("open durable journal failed", failure);
        }
    }

    /** @return 提交有序、不可变、可能为空的记录快照；线程安全，不执行 IO。 */
    public synchronized List<DurableRecord> records() { ensureOpen(); return List.copyOf(records.values()); }

    /** @param id 操作 ID。 @return 最新记录或 null；线程安全。 */
    public synchronized DurableRecord find(final String id) { ensureOpen(); return records.get(id); }

    /** 同一目标/实体不能有两个未解决意图；线程安全，无磁盘 IO。 */
    synchronized boolean hasUnresolved(final String target, final String id) {
        ensureOpen();
        return unresolved.containsKey(new EntityKey(target, id));
    }

    /** 返回到期的有界批次，不扫描已完成收据；线程安全，顺序稳定。 */
    synchronized List<DurableRecord> pending(final long now, final int limit) {
        ensureOpen();
        var selected = new ArrayList<DurableRecord>();
        for (String id : unresolved.values()) {
            DurableRecord record = records.get(id);
            if (record.state() == DurableRecord.State.PENDING && record.nextAttemptAt() <= now) {
                selected.add(record);
                if (selected.size() == limit) break;
            }
        }
        return List.copyOf(selected);
    }

    /** 刷盘成功后才更新内存视图；异常表示未知或未提交，调用方须重新打开核验。 */
    synchronized void append(final DurableRecord record) {
        ensureOpen();
        DurableRecord previous = records.get(record.operationId());
        if (previous != null && !previous.sameIntent(record)) throw error("operation id reused with different intent", null);
        if (previous == null && records.size() >= maxOperations) throw error("journal operation budget exceeded", null);
        try {
            byte[] frame = frame(record);
            if (Files.size(path) > maxBytes - frame.length) compact();
            if (Files.size(path) > maxBytes - frame.length) throw error("journal byte budget exceeded", null);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writeFully(channel, frame);
                channel.force(true);
            }
            records.put(record.operationId(), record);
            index(record);
        } catch (IOException failure) {
            poisoned = true;
            throw error("durable append failed; reopen before retry", failure);
        }
    }

    /** 原子替换为每操作的最新记录；保留完成收据，永不静默淘汰待执行/隔离数据。
     * 同步 IO，线程安全；不支持原子替换的文件系统明确失败。 */
    public synchronized void compact() {
        ensureOpen();
        Path temporary = path.resolveSibling("intents.compact");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(channel, ByteBuffer.allocate(Long.BYTES).putLong(MAGIC).array());
            for (DurableRecord record : records.values()) writeFully(channel, frame(record));
            channel.force(true);
        } catch (IOException failure) {
            throw error("compact durable journal failed", failure);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw error("atomic journal replacement failed", failure);
        }
    }

    /** 幂等关闭；调用方必须先停止并等待全部提交/flush。 */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            try { lock.release(); } finally { lockChannel.close(); }
        } catch (IOException failure) { throw error("close durable journal failed", failure); }
    }

    private void recover() throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw")) {
            if (file.length() > maxBytes) throw new IOException("journal exceeds byte budget");
            if (file.length() < Long.BYTES) {
                file.setLength(0); file.writeLong(MAGIC); file.getChannel().force(true);
            }
            file.seek(0);
            if (file.readLong() != MAGIC) throw new IOException("unsupported durable journal format");
            long boundary = Long.BYTES;
            while (boundary < file.length()) {
                file.seek(boundary);
                if (file.length() - boundary < 12) break;
                int size = file.readInt();
                int complement = file.readInt();
                int checksum = file.readInt();
                if (size < 1 || size > MAX_FRAME || complement != ~size) throw new IOException("invalid durable frame size");
                if (file.length() - file.getFilePointer() < size) break;
                byte[] payload = new byte[size]; file.readFully(payload);
                if (checksum(payload) != checksum) throw new IOException("durable journal checksum mismatch");
                DurableRecord record = decode(payload);
                DurableRecord previous = records.put(record.operationId(), record);
                if (previous != null && !previous.sameIntent(record)) throw new IOException("durable intent changed");
                if (records.size() > maxOperations) throw new IOException("journal exceeds operation budget");
                index(record);
                boundary = file.getFilePointer();
            }
            if (file.length() != boundary) { file.setLength(boundary); file.getChannel().force(true); }
        }
    }

    private byte[] frame(final DurableRecord record) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeUTF(record.operationId()); out.writeUTF(record.target()); out.writeLong(record.expectedVersion());
            out.writeUTF(record.state().name()); out.writeInt(record.attempts()); out.writeLong(record.nextAttemptAt());
            out.writeUTF(record.errorCode());
            byte[] envelope = new ZeroDataEnvelopeCodec().encode(record.envelope());
            out.writeInt(envelope.length); out.write(envelope);
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_FRAME) throw error("durable frame budget exceeded", null);
        return ByteBuffer.allocate(payload.length + 12).putInt(payload.length).putInt(~payload.length)
                .putInt(checksum(payload)).put(payload).array();
    }

    private DurableRecord decode(final byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String id = in.readUTF(); String target = in.readUTF(); long expected = in.readLong();
            var state = DurableRecord.State.valueOf(in.readUTF()); int attempts = in.readInt();
            long at = in.readLong(); String errorCode = in.readUTF(); int size = in.readInt();
            if (size < 1 || size != in.available()) throw new IOException("invalid envelope frame");
            return new DurableRecord(id, target, expected, new ZeroDataEnvelopeCodec().decode(in.readNBytes(size)),
                    state, attempts, at, errorCode);
        }
    }

    private static int checksum(final byte[] bytes) {
        var crc = new CRC32C(); crc.update(bytes); return (int) crc.getValue();
    }

    private void index(final DurableRecord record) {
        var key = new EntityKey(record.target(), record.envelope().id());
        if (record.state() == DurableRecord.State.APPLIED) {
            unresolved.remove(key, record.operationId());
        } else {
            unresolved.put(key, record.operationId());
        }
    }

    /** 日志内的实体身份，与后端目标绑定保持一致。 */
    private record EntityKey(String target, String id) { }

    private void writeFully(final FileChannel channel, final byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private void ensureOpen() {
        if (closed || poisoned) throw error("journal closed or requires recovery", null);
    }

    private static ZeroException error(final String message, final Throwable cause) {
        return ZeroException.of(DataErrorCode.PERSISTENCE_FLUSH_FAILED, message, cause);
    }
}
