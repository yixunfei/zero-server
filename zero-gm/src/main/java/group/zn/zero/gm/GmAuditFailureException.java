package group.zn.zero.gm;

import group.zn.zero.core.error.ZeroException;
import java.util.Objects;

/**
 * 携带审计阶段和四态业务提交语义的 GM 审计失败异常。
 *
 * <p>错误原因固定为 {@link GmErrorCode#AUDIT_HOOK_FAILED}；调用方必须结合
 * {@link #phase()} 与 {@link #businessCommitState()} 判断是否可能已经提交业务结果。
 * 特别是 COMMITTED / UNKNOWN 不得自动重试。</p>
 *
 * @author zn
 */
public final class GmAuditFailureException extends ZeroException {

    /** 审计失败阶段。 */
    private final GmAuditPhase phase;

    /** 审计失败时的业务提交状态。 */
    private final GmBusinessCommitState businessCommitState;

    /**
     * 创建 GM 审计失败异常。
     *
     * @param phase 审计阶段；不可为空。
     * @param businessCommitState 业务提交状态；不可为空。
     * @param cause 审计构造、转换或落地的原异常；不可为空。
     * @throws NullPointerException 当阶段、提交状态或 cause 为空时抛出。
     */
    GmAuditFailureException(
            final GmAuditPhase phase,
            final GmBusinessCommitState businessCommitState,
            final RuntimeException cause) {
        super(
                GmErrorCode.AUDIT_HOOK_FAILED,
                GmErrorCode.AUDIT_HOOK_FAILED.message(),
                Objects.requireNonNull(cause, "cause"));
        this.phase = Objects.requireNonNull(phase, "phase");
        this.businessCommitState = Objects.requireNonNull(businessCommitState, "businessCommitState");
    }

    /**
     * 返回审计失败阶段。
     *
     * @return 审计阶段；不可为空；线程安全。
     */
    public GmAuditPhase phase() {
        return phase;
    }

    /**
     * 返回失败时的业务提交状态。
     *
     * @return 四态业务提交状态；不可为空；线程安全。
     */
    public GmBusinessCommitState businessCommitState() {
        return businessCommitState;
    }

    /**
     * 返回不包含 cause message、业务身份、请求值或密钥的诊断字符串。
     *
     * @return 有界诊断文本；不可为空；线程安全。
     */
    @Override
    public String toString() {
        return "GmAuditFailureException{code='" + code()
                + "', phase=" + phase
                + ", businessCommitState=" + businessCommitState
                + '}';
    }
}
