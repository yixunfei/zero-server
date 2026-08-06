package group.zn.zero.data.redis;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCodec;
import java.util.Arrays;
import java.util.Objects;

/**
 * Redis 对象快照。
 *
 * @param dataKey 对象快照 key。
 * @param indexKey bucket 索引 key。
 * @param journalKey 追加日志 key。
 * @param id 编码后的对象 ID。
 * @param version 对象版本号。
 * @param envelopeBytes 数据对象信封字节。
 * @author zn
 */
public record RedisDataSnapshot(
        String dataKey,
        String indexKey,
        String journalKey,
        String id,
        long version,
        byte[] envelopeBytes) {

    /**
     * 创建 Redis 对象快照。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     */
    public RedisDataSnapshot {
        dataKey = Objects.requireNonNull(dataKey, "dataKey");
        indexKey = Objects.requireNonNull(indexKey, "indexKey");
        journalKey = Objects.requireNonNull(journalKey, "journalKey");
        id = Objects.requireNonNull(id, "id");
        envelopeBytes = Arrays.copyOf(
                Objects.requireNonNull(envelopeBytes, "envelopeBytes"),
                envelopeBytes.length);
    }

    /**
     * 基于统一数据对象信封创建 Redis 对象快照。
     *
     * @param envelope 数据对象信封；不可为空。
     * @param keyStrategy Redis key 策略；不可为空。
     * @param codec 信封 codec；不可为空。
     * @return Redis 对象快照；不可为空；线程安全。
     */
    public static RedisDataSnapshot fromEnvelope(
            final ZeroDataEnvelope envelope,
            final RedisDataKeyStrategy keyStrategy,
            final ZeroDataEnvelopeCodec codec) {
        ZeroDataEnvelope current = Objects.requireNonNull(envelope, "envelope");
        RedisDataKeyStrategy strategy = Objects.requireNonNull(keyStrategy, "keyStrategy");
        String id = current.id();
        return new RedisDataSnapshot(
                strategy.dataKey(current.namespace(), current.collection(), id),
                strategy.indexKey(current.namespace(), current.collection(), id),
                strategy.journalKey(current.namespace(), current.collection(), id),
                id,
                current.version(),
                Objects.requireNonNull(codec, "codec").encode(current));
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
}
