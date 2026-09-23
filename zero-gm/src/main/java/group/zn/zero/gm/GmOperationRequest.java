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
}
