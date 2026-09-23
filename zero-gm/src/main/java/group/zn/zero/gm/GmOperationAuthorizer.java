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

    public GmOperationAuthorizer(final GmOperationAuthorizationPolicy policy,
                                 final Consumer<GmAuthorizationAuditEvent> audit) {
        this(policy, policy.sourceIpPolicy(), GmApprovalVerifier.contextState(), audit);
    }

    /** Creates an authorizer with application-provided source and approval policies. */
    public GmOperationAuthorizer(final GmOperationAuthorizationPolicy policy,
                                 final GmSourceIpPolicy sourceIpPolicy,
                                 final GmApprovalVerifier approvalVerifier,
                                 final Consumer<GmAuthorizationAuditEvent> audit) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.sourceIpPolicy = Objects.requireNonNull(sourceIpPolicy, "sourceIpPolicy");
        this.approvalVerifier = Objects.requireNonNull(approvalVerifier, "approvalVerifier");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public void authorize(final GmCommandContext context, final String target,
                          final String approvalToken, final String secondReviewer) {
        Objects.requireNonNull(context, "context");
        GmAuthorizationFailureReason reason = failure(context, target, approvalToken, secondReviewer);
        if (reason != null) {
            audit.accept(new GmAuthorizationAuditEvent(
                    "operation", ref(context.operator()), ref(context.operatorIp()), ref(target), reason));
            throw ZeroException.of(GmErrorCode.OPERATION_AUTHORIZATION_DENIED);
        }
    }

    private GmAuthorizationFailureReason failure(GmCommandContext c, String target, String token, String reviewer) {
        if (c.operator().isBlank()) return GmAuthorizationFailureReason.MISSING_OPERATOR;
        if (c.operatorIp().isBlank()) return GmAuthorizationFailureReason.MISSING_SOURCE;
        if (target == null || target.isBlank()) return GmAuthorizationFailureReason.MISSING_TARGET;
        if (!policy.allowedRoles().isEmpty() && policy.allowedRoles().stream().noneMatch(c.roles()::contains))
            return GmAuthorizationFailureReason.ROLE_PERMISSION_DENIED;
        if (!c.permissions().containsAll(policy.requiredPermissions()))
            return GmAuthorizationFailureReason.ROLE_PERMISSION_DENIED;
        if (!sourceIpPolicy.allows(c.operatorIp())) return GmAuthorizationFailureReason.SOURCE_IP_DENIED;
        if (policy.approvalRequired()) {
            GmApprovalDecision decision = Objects.requireNonNull(
                    approvalVerifier.verify(c, target, token), "approval decision");
            if (decision != GmApprovalDecision.APPROVED) {
                return decision == GmApprovalDecision.INVALID
                        ? GmAuthorizationFailureReason.APPROVAL_TOKEN_INVALID
                        : GmAuthorizationFailureReason.APPROVAL_REQUIRED;
            }
        }
        if (policy.dualReviewRequired() && (reviewer == null || reviewer.isBlank()
                || reviewer.equals(c.operator()))) return GmAuthorizationFailureReason.DUAL_REVIEW_REQUIRED;
        return null;
    }

    private String ref(String value) {
        return "redacted:" + Integer.toHexString(Objects.toString(value, "").hashCode());
    }
}
