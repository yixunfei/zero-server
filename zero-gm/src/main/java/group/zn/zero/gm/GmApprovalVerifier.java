package group.zn.zero.gm;

/** Pluggable verifier for an approval fact supplied by a trusted upstream system. */
@FunctionalInterface
public interface GmApprovalVerifier {
    /** Verifies an approval reference and opaque token for the current operation. */
    GmApprovalDecision verify(GmCommandContext context, String target, String approvalToken);

    /** Verifier that accepts only the explicit APPROVED context state. */
    static GmApprovalVerifier contextState() {
        return (context, target, token) -> "APPROVED".equalsIgnoreCase(context.approvalState())
                ? GmApprovalDecision.APPROVED
                : GmApprovalDecision.REJECTED;
    }
}
