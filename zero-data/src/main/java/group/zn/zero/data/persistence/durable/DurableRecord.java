package group.zn.zero.data.persistence.durable;

import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeCodec;
import java.util.Arrays;
import java.util.Objects;

/** 耐久意图及其处理结果；不是“后端已成功”记录；不可变、线程安全。 @author zn */
public record DurableRecord(String operationId, String target, long expectedVersion, ZeroDataEnvelope envelope,
                            State state, int attempts, long nextAttemptAt, String errorCode) {
    /** 持久化处理状态；隔离记录必须由调用方显式恢复。 */
    public enum State { PENDING, APPLIED, QUARANTINED }

    /** 校验可恢复的 CAS 意图；待写版本必须是期望版本的下一版。 */
    public DurableRecord {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(errorCode, "errorCode");
        if (operationId == null || operationId.isBlank() || operationId.length() > 256
                || target == null || target.isBlank() || target.length() > 256
                || expectedVersion < 0 || expectedVersion == Long.MAX_VALUE
                || envelope.version() != expectedVersion + 1 || attempts < 0 || nextAttemptAt < 0) {
            throw new IllegalArgumentException("invalid durable intent");
        }
    }

    /** @param other 待核验意图。 @return 同操作 ID、目标、CAS 和完整快照才允许幂等提交。 */
    public boolean sameIntent(final DurableRecord other) {
        return operationId.equals(other.operationId) && target.equals(other.target)
                && expectedVersion == other.expectedVersion && sameEnvelope(envelope, other.envelope);
    }

    /** @param left 信封。 @param right 信封。 @return 包含元数据的完整字节相同；线程安全。 */
    public static boolean sameEnvelope(final ZeroDataEnvelope left, final ZeroDataEnvelope right) {
        var codec = new ZeroDataEnvelopeCodec();
        return Arrays.equals(codec.encode(left), codec.encode(right));
    }

    /** @param next 下一状态。 @param count 已尝试次数。 @param at 下次时间。 @param code 错误码。
     * @return 新结果；不改变意图。 */
    public DurableRecord result(final State next, final int count, final long at, final String code) {
        return new DurableRecord(operationId, target, expectedVersion, envelope, next, count, at, code);
    }
}
