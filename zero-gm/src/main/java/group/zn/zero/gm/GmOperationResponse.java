package group.zn.zero.gm;

import java.util.Objects;

/**
 * 保留业务提交状态与基础设施失败的不可变响应；不包含底层异常文本。
 * @param code 稳定领域码。 @param message 安全说明。 @param result 可空业务结果。
 * @param traceId 本次请求追踪。 @param businessCommitState 业务提交事实。
 * @param auditFailed 审计未获确认。 @param idempotencyFailed 幂等记账未获确认。
 * @author zn
 */
public record GmOperationResponse(String code, String message, GmCommandExecutionResult result, String traceId,
        GmBusinessCommitState businessCommitState, boolean auditFailed, boolean idempotencyFailed) {
    /** 校验不可变字段；构造无外部副作用。 @throws NullPointerException 必填字段为空。 */
    public GmOperationResponse {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(businessCommitState, "businessCommitState");
    }

    /** @param result 已校验业务结果。 @param traceId 本次追踪。 @return 安全结果，线程安全无副作用。 */
    public static GmOperationResponse success(GmCommandExecutionResult result, String traceId) {
        Objects.requireNonNull(result, "result");
        var state = result.dryRun() ? GmBusinessCommitState.NOT_APPLICABLE
                : result.success() ? GmBusinessCommitState.COMMITTED : GmBusinessCommitState.NOT_COMMITTED;
        return new GmOperationResponse(result.success() ? "ZERO-OK" : result.errorCode().code(),
                result.success() ? "success" : result.errorCode().message(), result, traceId, state, false, false);
    }

    /** @param code 拒绝码。 @param traceId 追踪。 @return 未提交的拒绝响应，无数据变更，线程安全。 */
    public static GmOperationResponse rejected(GmErrorCode code, String traceId) {
        return failed(code, traceId, GmBusinessCommitState.NOT_COMMITTED, false);
    }

    /** 构造安全失败响应；无数据变更，包内调用。 */
    static GmOperationResponse failed(GmErrorCode code, String traceId, GmBusinessCommitState state, boolean auditFailed) {
        return new GmOperationResponse(code.code(), code.message(), null, traceId, state, auditFailed, false);
    }

    /** 增加审计失败信号而保留原领域码和业务结果。 */
    GmOperationResponse withAuditFailure() {
        var next = "ZERO-OK".equals(code) ? GmErrorCode.AUDIT_HOOK_FAILED : null;
        return new GmOperationResponse(next == null ? code : next.code(), next == null ? message : next.message(),
                result, traceId, businessCommitState, true, idempotencyFailed);
    }

    /** 增加记账失败信号；成功业务保留结果和 COMMITTED，但不伪装为可盲重试成功。 */
    GmOperationResponse withIdempotencyFailure() {
        var next = "ZERO-OK".equals(code) ? GmErrorCode.IDEMPOTENCY_STORE_FAILED : null;
        return new GmOperationResponse(next == null ? code : next.code(), next == null ? message : next.message(),
                result, traceId, businessCommitState, auditFailed, true);
    }

    /** 返回当前请求追踪，不复用历史请求 trace。 */
    GmOperationResponse withTrace(String trace) {
        return new GmOperationResponse(code, message, result, trace, businessCommitState, auditFailed, idempotencyFailed);
    }
}
