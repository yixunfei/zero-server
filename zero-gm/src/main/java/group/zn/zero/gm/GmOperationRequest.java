package group.zn.zero.gm;

import java.util.Objects;

/** Transport-neutral request envelope for one GM operation. */
public record GmOperationRequest(
        GmCommandContext context,
        String rawCommand,
        String target,
        String approvalToken,
        String secondReviewer,
        String idempotencyKey,
        boolean dryRun) {
    public GmOperationRequest(
            final GmCommandContext context,
            final String rawCommand,
            final String target,
            final String approvalToken,
            final String secondReviewer,
            final boolean dryRun) {
        this(context, rawCommand, target, approvalToken, secondReviewer, "", dryRun);
    }

    public GmOperationRequest {
        context = Objects.requireNonNull(context, "context");
        rawCommand = Objects.requireNonNull(rawCommand, "rawCommand");
        target = target == null ? "" : target;
        approvalToken = approvalToken == null ? "" : approvalToken;
        secondReviewer = secondReviewer == null ? "" : secondReviewer;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
    }
    /** @return 固定安全诊断文本；不输出身份、令牌或请求值，线程安全且无变更。 */
    @Override
    public String toString() {
        return "GmOperationRequest{dryRun=" + dryRun + ", request=[REDACTED]}";
    }
}
