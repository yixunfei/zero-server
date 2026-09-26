package group.zn.zero.gm;

/** Pluggable verifier for an approval fact supplied by a trusted upstream system. */
@FunctionalInterface
public interface GmApprovalVerifier {
    /** Verifies an approval reference and opaque token for the current operation. */
    GmApprovalDecision verify(GmCommandContext context, String target, String approvalToken);

    /** Verifier that accepts only the explicit APPROVED context state.
     *
     * <p>This helper is retained for trusted adapters that have already validated
     * the approval reference. It must not be used as the default verifier at a
     * public authorization boundary.
     */
    static GmApprovalVerifier contextState() {
        return (context, target, token) -> "APPROVED".equalsIgnoreCase(context.approvalState())
                ? GmApprovalDecision.APPROVED
                : GmApprovalDecision.REJECTED;
    }

    /** Fail-closed verifier for callers that have not configured an approval service. */
    static GmApprovalVerifier failClosed() {
        return (context, target, token) -> GmApprovalDecision.REJECTED;
    }
}
