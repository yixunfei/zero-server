package group.zn.zero.net.kcp;

import java.time.Duration;

/** 不可变 FEC 参数；wireId=0 禁用，1=XOR，2=Reed-Solomon。 @author zn */
public record KcpFecOptions(int wireId, int dataShards, int parityShards, Duration flushDelay,
        Duration expiry, int maxGroups, long maxBytes) {
    /** 构造校验参数，不创建资源。 */
    public KcpFecOptions {
        if (wireId < 0 || wireId > 65535 || dataShards < 1 || dataShards > 32 || parityShards < 0 || parityShards > 16
                || wireId == 0 && (dataShards != 1 || parityShards != 0) || wireId != 0 && parityShards == 0
                || wireId == 1 && parityShards != 1 || flushDelay == null || expiry == null
                || flushDelay.toMillis() < 1 || flushDelay.toMillis() > 100 || expiry.toMillis() < flushDelay.toMillis()
                || expiry.toSeconds() > 30 || maxGroups < 1 || maxGroups > 64 || maxBytes < 4096 || maxBytes > (64L << 20)) {
            throw new IllegalArgumentException("invalid KCP FEC options");
        }
    }
    /** @return 禁用 FEC。 */
    public static KcpFecOptions none() { return new KcpFecOptions(0, 1, 0, Duration.ofMillis(1), Duration.ofSeconds(1), 16, 262144); }
    /** @param data 数据 shard 数。 @return XOR 预设。 */
    public static KcpFecOptions xor(final int data) { return new KcpFecOptions(1, data, 1, Duration.ofMillis(2), Duration.ofSeconds(1), 16, 262144); }
    /** @param data 数据 shard 数。 @param parity 校验 shard 数。 @return RS 预设。 */
    public static KcpFecOptions reedSolomon(final int data, final int parity) { return new KcpFecOptions(2, data, parity, Duration.ofMillis(5), Duration.ofSeconds(2), 32, 524288); }
}
