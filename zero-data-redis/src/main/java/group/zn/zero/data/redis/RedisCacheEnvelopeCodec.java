package group.zn.zero.data.redis;

import group.zn.zero.cache.CacheErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Redis 缓存信封 codec。
 *
 * <p>该 codec 只负责缓存 envelope，不复用持久化 data envelope。
 *
 * @author zn
 */
public final class RedisCacheEnvelopeCodec {

    /**
     * 编码缓存信封。
     *
     * @param envelope 缓存信封；不可为空。
     * @return 编码字节；不可为空；线程安全。
     */
    public byte[] encode(final RedisCacheEnvelope envelope) {
        RedisCacheEnvelope current = Objects.requireNonNull(envelope, "envelope");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeLong(current.cacheVersion());
            output.writeLong(current.entityVersion());
            output.writeInt(current.schemaVersion());
            output.writeUTF(current.codecName());
            output.writeLong(current.createdAtEpochMillis());
            output.writeLong(current.expireAtEpochMillis());
            output.writeBoolean(current.negative());
            byte[] payload = current.payload();
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw ZeroException.of(CacheErrorCode.SERIALIZE_FAILED, "redis cache envelope encode failed", ex);
        }
    }

    /**
     * 解码缓存信封。
     *
     * @param bytes 编码字节；不可为空。
     * @return 缓存信封；不可为空；线程安全。
     */
    public RedisCacheEnvelope decode(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            long cacheVersion = input.readLong();
            long entityVersion = input.readLong();
            int schemaVersion = input.readInt();
            String codecName = input.readUTF();
            long createdAt = input.readLong();
            long expireAt = input.readLong();
            boolean negative = input.readBoolean();
            int payloadLength = input.readInt();
            if (payloadLength < 0) {
                throw ZeroException.of(
                        CacheErrorCode.DESERIALIZE_FAILED,
                        "redis cache envelope payload length invalid",
                        null);
            }
            byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength) {
                throw ZeroException.of(
                        CacheErrorCode.DESERIALIZE_FAILED,
                        "redis cache envelope payload truncated",
                        null);
            }
            return new RedisCacheEnvelope(
                    cacheVersion,
                    entityVersion,
                    schemaVersion,
                    codecName,
                    createdAt,
                    expireAt,
                    negative,
                    payload);
        } catch (IOException ex) {
            throw ZeroException.of(CacheErrorCode.DESERIALIZE_FAILED, "redis cache envelope decode failed", ex);
        }
    }
}
