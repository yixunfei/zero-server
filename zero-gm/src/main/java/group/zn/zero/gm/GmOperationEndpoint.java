package group.zn.zero.gm;

import group.zn.zero.core.error.ZeroException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Standard framework entry point usable by REST, RPC, CLI or internal adapters. */
public final class GmOperationEndpoint {
    private final GmCommandExecutor executor;
    private final GmOperationAuthorizer authorizer;
    private final GmIdempotencyStore idempotencyStore;

    /** Creates an entry point with explicit executor and authorization policy. */
    public GmOperationEndpoint(final GmCommandExecutor executor, final GmOperationAuthorizer authorizer) {
        this(executor, authorizer, null);
    }

    /** Creates an entry point with an explicit idempotency store. */
    public GmOperationEndpoint(
            final GmCommandExecutor executor,
            final GmOperationAuthorizer authorizer,
            final GmIdempotencyStore idempotencyStore) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.idempotencyStore = idempotencyStore;
    }

    /** Executes or previews one operation and converts failures to a stable response. */
    public GmOperationResponse handle(final GmOperationRequest request) {
        Objects.requireNonNull(request, "request");
        String key = request.idempotencyKey();
        String fingerprint = fingerprint(request);
        boolean idempotent = idempotencyStore != null && !key.isBlank() && !request.dryRun();
        Duration ttl = Duration.ofMinutes(10);
        Instant now = Instant.now();
        if (idempotent) {
            GmIdempotencyStore.Claim claim = idempotencyStore.claim(key, fingerprint, now, ttl);
            if (claim.state() == GmIdempotencyStore.State.COMPLETED) {
                return claim.response();
            }
            if (claim.state() == GmIdempotencyStore.State.CONFLICT
                    || claim.state() == GmIdempotencyStore.State.IN_PROGRESS) {
                return GmOperationResponse.rejected(GmErrorCode.COMMAND_REJECTED, request.context().traceId());
            }
        }
        try {
            authorizer.authorize(request.context(), request.target(), request.approvalToken(), request.secondReviewer());
            GmCommandExecutionResult result = request.dryRun()
                    ? executor.dryRun(request.context(), request.rawCommand())
                    : executor.execute(request.context(), request.rawCommand());
            GmOperationResponse response = GmOperationResponse.success(result, request.context().traceId());
            if (idempotent) {
                idempotencyStore.complete(key, fingerprint, response, now.plus(ttl));
            }
            return response;
        } catch (ZeroException exception) {
            if (idempotent) {
                idempotencyStore.fail(key, fingerprint, now.plus(ttl));
            }
            return GmOperationResponse.rejected(toGmCode(exception), request.context().traceId());
        }
    }

    /** Stable, non-reversible operation fingerprint that never stores raw input. */
    public static String fingerprint(final GmOperationRequest request) {
        Objects.requireNonNull(request, "request");
        String canonical = request.context().operator() + "\u0000"
                + request.target() + "\u0000"
                + request.rawCommand() + "\u0000"
                + request.dryRun();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private GmErrorCode toGmCode(final ZeroException exception) {
        return exception.errorCode() instanceof GmErrorCode gmError ? gmError : GmErrorCode.COMMAND_REJECTED;
    }
}
