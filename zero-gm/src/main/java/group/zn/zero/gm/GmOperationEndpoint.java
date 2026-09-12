package group.zn.zero.gm;

import group.zn.zero.core.error.ZeroException;
import java.util.Objects;

/** Standard framework entry point usable by REST, RPC, CLI or internal adapters. */
public final class GmOperationEndpoint {
    private final GmCommandExecutor executor;
    private final GmOperationAuthorizer authorizer;

    /** Creates an entry point with explicit executor and authorization policy. */
    public GmOperationEndpoint(final GmCommandExecutor executor, final GmOperationAuthorizer authorizer) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    }

    /** Executes or previews one operation and converts failures to a stable response. */
    public GmOperationResponse handle(final GmOperationRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            authorizer.authorize(request.context(), request.target(), request.approvalToken(), request.secondReviewer());
            GmCommandExecutionResult result = request.dryRun()
                    ? executor.dryRun(request.context(), request.rawCommand())
                    : executor.execute(request.context(), request.rawCommand());
            return GmOperationResponse.success(result, request.context().traceId());
        } catch (ZeroException exception) {
            return GmOperationResponse.rejected(toGmCode(exception), request.context().traceId());
        }
    }

    private GmErrorCode toGmCode(final ZeroException exception) {
        return exception.errorCode() instanceof GmErrorCode gmError ? gmError : GmErrorCode.COMMAND_REJECTED;
    }
}
