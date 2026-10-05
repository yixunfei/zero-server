package group.zn.zero.statesync;

import java.util.Map;

/** Immutable synchronization envelope. */
public record SyncEnvelope(String sceneId, String observerId, long syncSeq, long baselineVersion,
                           long stateVersion, Kind kind, Map<String, Object> payload, String payloadHash) {
    /** 创建并校验 payload 内容摘要；普通 Map 会复制并重新计算。 */
    public SyncEnvelope(final String sceneId, final String observerId, final long syncSeq,
            final long baselineVersion, final long stateVersion, final Kind kind,
            final Map<String, Object> payload) {
        this(sceneId, observerId, syncSeq, baselineVersion, stateVersion, kind, payload, null);
    }

    /** 校验公共输入；仅复用框架深快照的摘要，显式摘要仍必须匹配。 */
    public SyncEnvelope {
        if (sceneId == null || observerId == null || kind == null || payload == null) throw new NullPointerException();
        String computedHash;
        if (payload instanceof PreparedSyncPayload prepared) {
            computedHash = prepared.hash();
        } else {
            payload = Map.copyOf(payload);
            computedHash = SyncPayloadHash.compute(payload);
        }
        payloadHash = payloadHash == null ? computedHash : requireHash(payloadHash, computedHash);
    }

    /**
     * 在发送方所属执行域深快照并计算一次摘要，之后多个观察者可共享此 Map。
     *
     * @param payload 原始数据；顶层键和值不可为空；嵌套容器允许 null。
     * @return 深不可变 Map；保持输入顺序，可为空，线程安全；Collection 转为不可变 List。
     * @throws IllegalArgumentException 叶子不是 String、基本包装类型、BigInteger、BigDecimal 或 UUID
     *     时抛出；数组应先转换为 List，自定义对象应先投影为上述数据类型。
     * @throws NullPointerException 顶层 Map、键或值为空时抛出。
     */
    public static Map<String, Object> preparePayload(final Map<String, Object> payload) {
        return PreparedSyncPayload.prepare(payload);
    }

    private static String requireHash(final String value, final String computedHash) {
        if (value.isBlank()) throw new IllegalArgumentException("payloadHash must not be blank");
        if (!computedHash.equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("payloadHash does not match payload");
        }
        return computedHash;
    }

    /** 同步消息类型。 */
    public enum Kind { SNAPSHOT, DELTA }
}
