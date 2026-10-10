package group.zn.zero.gm;

import group.zn.zero.core.error.ZeroException;
import java.util.Objects;
import java.util.function.Consumer;

/** Narrow authorization slice for a GM operation; does not change command APIs. */
public final class GmOperationAuthorizer {
    private final GmOperationAuthorizationPolicy policy;
    private final GmSourceIpPolicy sourceIpPolicy;
    private final GmApprovalVerifier approvalVerifier;
    private final Consumer<GmAuthorizationAuditEvent> audit;
    /** 可信第二复核人验证端口，缺省拒绝。 */
    private final GmSecondReviewerVerifier reviewerVerifier;

    public GmOperationAuthorizer(final GmOperationAuthorizationPolicy policy,
                                 final Consumer<GmAuthorizationAuditEvent> audit) {
        this(policy, policy.sourceIpPolicy(), GmApprovalVerifier.failClosed(), audit);
    }

    /** Creates an authorizer with application-provided source and approval policies. */
    public GmOperationAuthorizer(final GmOperationAuthorizationPolicy policy,
                                 final GmSourceIpPolicy sourceIpPolicy,
                                 final GmApprovalVerifier approvalVerifier,
                                 final Consumer<GmAuthorizationAuditEvent> audit) {
        this(policy, sourceIpPolicy, approvalVerifier, GmSecondReviewerVerifier.failClosed(), audit);
    }

    /**
     * 创建显式可信复核边界；构造不执行 IO，线程安全取决于注入端口。
     * @param policy 访问策略。 @param sourceIpPolicy 显式来源约束。
     * @param approvalVerifier 审批端口。 @param reviewerVerifier 独立复核端口。
     * @param audit 拒绝审计消费者。 @throws NullPointerException 任一依赖为空。
     */
    public GmOperationAuthorizer(final GmOperationAuthorizationPolicy policy,
            final GmSourceIpPolicy sourceIpPolicy, final GmApprovalVerifier approvalVerifier,
            final GmSecondReviewerVerifier reviewerVerifier, final Consumer<GmAuthorizationAuditEvent> audit) {
        this.reviewerVerifier = Objects.requireNonNull(reviewerVerifier, "reviewerVerifier");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sourceIpPolicy = Objects.requireNonNull(sourceIpPolicy, "sourceIpPolicy");
        this.approvalVerifier = Objects.requireNonNull(approvalVerifier, "approvalVerifier");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    void authorize(final GmCommandContext context, final String target,
                          final String approvalToken, final String secondReviewer) {
        authorize(context, target, approvalToken, secondReviewer, false, null);
    }

    /** 使用实际命令目标校验声明目标，并合并指令审批要求；只由标准端点调用。 */
    void authorize(final GmCommandContext context, final GmCommandResolution resolution,
                   final String declaredTarget, final String approvalToken, final String secondReviewer) {
        GmCommandDefinition definition = resolution.definition();
        String targetParameter = definition.targetParameterName();
        String target = targetParameter.isBlank() ? declaredTarget : resolution.namedArguments().get(targetParameter);
        if (!Objects.equals(target, declaredTarget)) {
            reject(context, target, GmAuthorizationFailureReason.TARGET_MISMATCH);
        }
        authorize(context, target, approvalToken, secondReviewer, definition.approvalRequired() || definition.risk().requiresApproval(), resolution.toRequest(context));
    }

    private void authorize(final GmCommandContext context, final String target, final String approvalToken,
                           final String secondReviewer, final boolean commandApprovalRequired, final GmCommandExecutionRequest request) {
        Objects.requireNonNull(context, "context");
        GmAuthorizationFailureReason reason;
        try {
            reason = failure(context, target, approvalToken, secondReviewer, commandApprovalRequired, request);
        } catch (RuntimeException failure) {
            reject(context, target, GmAuthorizationFailureReason.VERIFICATION_UNAVAILABLE, failure);
            return;
        }
        if (reason != null) {
            reject(context, target, reason);
        }
    }

    private void reject(GmCommandContext context, String target, GmAuthorizationFailureReason reason) {
        reject(context, target, reason, null);
    }

    private void reject(GmCommandContext context, String target, GmAuthorizationFailureReason reason,
            RuntimeException verificationFailure) {
        ZeroException denied = ZeroException.of(GmErrorCode.OPERATION_AUTHORIZATION_DENIED);
        if (verificationFailure != null) denied.addSuppressed(verificationFailure);
        try {
            audit.accept(new GmAuthorizationAuditEvent("operation", "[REDACTED]", "[REDACTED]", "[REDACTED]", reason));
        } catch (RuntimeException failure) {
            denied.addSuppressed(new GmAuditFailureException(GmAuditPhase.REJECTED,
                    GmBusinessCommitState.NOT_COMMITTED, failure));
        }
        throw denied;
    }

    private GmAuthorizationFailureReason failure(GmCommandContext c, String target, String token, String reviewer,
                                                 boolean commandApprovalRequired, GmCommandExecutionRequest request) {
        if (policy.allowedRoles().isEmpty() && policy.requiredPermissions().isEmpty()
                && policy.allowedSourceIps().isEmpty()) return GmAuthorizationFailureReason.POLICY_NOT_CONFIGURED;
        if (c.operator().isBlank()) return GmAuthorizationFailureReason.MISSING_OPERATOR;
        if (c.operatorIp().isBlank()) return GmAuthorizationFailureReason.MISSING_SOURCE;
        if (target == null || target.isBlank()) return GmAuthorizationFailureReason.MISSING_TARGET;
        if (!policy.allowedRoles().isEmpty() && policy.allowedRoles().stream().noneMatch(c.roles()::contains))
            return GmAuthorizationFailureReason.ROLE_PERMISSION_DENIED;
        if (!c.permissions().containsAll(policy.requiredPermissions()))
            return GmAuthorizationFailureReason.ROLE_PERMISSION_DENIED;
        if (!sourceIpPolicy.allows(c.operatorIp())) return GmAuthorizationFailureReason.SOURCE_IP_DENIED;
        if (policy.approvalRequired() || policy.dualReviewRequired() || commandApprovalRequired) {
            GmApprovalDecision decision = Objects.requireNonNull(
                    approvalVerifier.verify(c, target, token), "approval decision");
            if (decision != GmApprovalDecision.APPROVED) {
                return decision == GmApprovalDecision.INVALID
                        ? GmAuthorizationFailureReason.APPROVAL_TOKEN_INVALID
                        : GmAuthorizationFailureReason.APPROVAL_REQUIRED;
            }
        }
        if (policy.dualReviewRequired() || request != null && request.definition().risk().requiresDualReview()) {
            if (request == null || reviewer == null || reviewer.isBlank() || reviewer.equals(c.operator()))
                return GmAuthorizationFailureReason.DUAL_REVIEW_REQUIRED;
            String verified = reviewerVerifier.verify(request, target, token, reviewer);
            if (verified == null || verified.isBlank() || verified.equals(c.operator()) || !verified.equals(reviewer))
                return GmAuthorizationFailureReason.DUAL_REVIEW_REQUIRED;
        }
        return null;
    }

}
