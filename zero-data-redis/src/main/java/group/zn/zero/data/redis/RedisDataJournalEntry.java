package group.zn.zero.data.redis;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCodec;
import java.util.Arrays;
import java.util.Objects;

/**
 * Redis 数据追加日志记录。
 *
 * @param namespace 数据命名空间。
 * @param collection 数据集合名称。
 * @param id 编码后的对象 ID。
 * @param version 对象版本号。
 * @param operation 操作类型。
 * @param writtenAtEpochMillis 写入时间，Unix epoch 毫秒。
 * @param envelopeBytes 数据对象信封字节；删除操作为空数组。
 * @author zn
 */
public record RedisDataJournalEntry(
        String namespace,
        String collection,
        String id,
        long version,
        RedisDataJournalOperation operation,
        long writtenAtEpochMillis,
        byte[] envelopeBytes) {

    /**
     * 创建 Redis 数据追加日志记录。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     * @throws IllegalArgumentException 当版本或写入时间非法时抛出。
     */
    public RedisDataJournalEntry {
        namespace = requireText(namespace, "namespace");
        collection = requireText(collection, "collection");
        id = requireText(id, "id");
        if (version <= 0L) {
            throw new IllegalArgumentException("version must be positive");
        }
        operation = Objects.requireNonNull(operation, "operation");
        if (writtenAtEpochMillis < 0L) {
            throw new IllegalArgumentException("writtenAtEpochMillis must be non-negative");
        }
        envelopeBytes = Arrays.copyOf(
                Objects.requireNonNull(envelopeBytes, "envelopeBytes"),
                envelopeBytes.length);
    }

    /**
     * 创建 PUT 追加日志。
     *
     * @param envelope 数据对象信封；不可为空。
     * @param codec 信封 codec；不可为空。
     * @return PUT 追加日志；不可为空；线程安全。
     */
    public static RedisDataJournalEntry put(final ZeroDataEnvelope envelope, final ZeroDataEnvelopeCodec codec) {
        ZeroDataEnvelope current = Objects.requireNonNull(envelope, "envelope");
        return new RedisDataJournalEntry(
                current.namespace(),
                current.collection(),
                current.id(),
                current.version(),
                RedisDataJournalOperation.PUT,
                current.encodedAtEpochMillis(),
                Objects.requireNonNull(codec, "codec").encode(current));
    }

    /**
     * 创建 DELETE 追加日志。
     *
     * @param namespace 数据命名空间；不可为空。
     * @param collection 数据集合名称；不可为空。
     * @param id 编码后的对象 ID；不可为空。
     * @param version 被删除对象的最后版本。
     * @param writtenAtEpochMillis 写入时间，Unix epoch 毫秒。
     * @return DELETE 追加日志；不可为空；线程安全。
     */
    public static RedisDataJournalEntry delete(
            final String namespace,
            final String collection,
            final String id,
            final long version,
            final long writtenAtEpochMillis) {
        return new RedisDataJournalEntry(
                namespace,
                collection,
                id,
                version,
                RedisDataJournalOperation.DELETE,
                writtenAtEpochMillis,
                new byte[0]);
    }

    /**
     * 返回信封字节副本。
     *
     * @return 信封字节副本；不可为空；有序；可能为空；线程安全。
     */
    @Override
    public byte[] envelopeBytes() {
        return Arrays.copyOf(envelopeBytes, envelopeBytes.length);
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
