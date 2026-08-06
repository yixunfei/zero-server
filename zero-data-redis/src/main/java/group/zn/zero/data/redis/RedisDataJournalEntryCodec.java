package group.zn.zero.data.redis;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import java.util.Arrays;
import java.util.Objects;

/**
 * Redis 数据追加日志记录 zcode 编解码器。
 *
 * @author zn
 */
public final class RedisDataJournalEntryCodec {

    /**
     * 追加日志记录魔数。
     */
    private static final byte[] MAGIC = new byte[] {'Z', 'D', 'J', '1'};

    /**
     * 创建 Redis 数据追加日志记录 zcode 编解码器。
     */
    public RedisDataJournalEntryCodec() {
    }

    /**
     * 编码追加日志记录。
     *
     * @param entry 追加日志记录；不可为空。
     * @return zcode 字节；不可为空；有序；可能为空；线程安全。
     */
    public byte[] encode(final RedisDataJournalEntry entry) {
        RedisDataJournalEntry current = Objects.requireNonNull(entry, "entry");
        try {
            byte[] envelopeBytes = current.envelopeBytes();
            ZeroWriter writer = new ZeroWriter(envelopeBytes.length + 96);
            writer.writeBytes(MAGIC);
            writer.writeString(current.namespace());
            writer.writeString(current.collection());
            writer.writeString(current.id());
            writer.writeLong(current.version());
            writer.writeString(current.operation().name());
            writer.writeLong(current.writtenAtEpochMillis());
            writer.writeByteArray(envelopeBytes);
            return writer.toByteArray();
        } catch (RuntimeException ex) {
            throw ZeroException.of(DataErrorCode.WRITE_FAILED, "encode redis data journal entry failed", ex);
        }
    }

    /**
     * 解码追加日志记录。
     *
     * @param bytes zcode 字节；不可为空。
     * @return 追加日志记录；不可为空；线程安全。
     */
    public RedisDataJournalEntry decode(final byte[] bytes) {
        byte[] current = Objects.requireNonNull(bytes, "bytes");
        try {
            ZeroReader reader = new ZeroReader(current);
            byte[] magic = reader.readBytes(MAGIC.length);
            if (!Arrays.equals(MAGIC, magic)) {
                throw ZeroException.of(DataErrorCode.READ_FAILED, "invalid redis data journal entry magic", null);
            }
            RedisDataJournalEntry entry = new RedisDataJournalEntry(
                    reader.readString(),
                    reader.readString(),
                    reader.readString(),
                    reader.readLong(),
                    RedisDataJournalOperation.valueOf(reader.readString()),
                    reader.readLong(),
                    reader.readByteArray());
            if (reader.isReadable()) {
                throw ZeroException.of(DataErrorCode.READ_FAILED, "redis data journal entry has trailing bytes", null);
            }
            return entry;
        } catch (RuntimeException ex) {
            if (ex instanceof ZeroException zeroException && zeroException.errorCode() == DataErrorCode.READ_FAILED) {
                throw zeroException;
            }
            throw ZeroException.of(DataErrorCode.READ_FAILED, "decode redis data journal entry failed", ex);
        }
    }
}
