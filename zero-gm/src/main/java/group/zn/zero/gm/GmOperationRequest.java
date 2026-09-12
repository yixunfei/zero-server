package group.zn.zero.gm;

import java.util.Objects;

/** Transport-neutral request envelope for one GM operation. */
public record GmOperationRequest(
        GmCommandContext context,
        String rawCommand,
        String target,
        String approvalToken,
        String secondReviewer,
        boolean dryRun) {
    public GmOperationRequest {
        context = Objects.requireNonNull(context, "context");
        rawCommand = Objects.requireNonNull(rawCommand, "rawCommand");
        target = target == null ? "" : target;
        approvalToken = approvalToken == null ? "" : approvalToken;
        secondReviewer = secondReviewer == null ? "" : secondReviewer;
    }
}
