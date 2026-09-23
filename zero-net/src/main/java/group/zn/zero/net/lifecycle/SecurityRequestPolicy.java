package group.zn.zero.net.lifecycle;

import group.zn.zero.net.IConnection;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.security.ReplayProtection;
import group.zn.zero.security.SecurityContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Explicit request-level security gate for replay and authorization checks. */
public final class SecurityRequestPolicy {
    private final ReplayProtection replayProtection;

    public SecurityRequestPolicy(final ReplayProtection replayProtection) {
        this.replayProtection = Objects.requireNonNull(replayProtection, "replayProtection");
    }

    public CompletionStage<NetworkAdmissionDecision> check(
            final IConnection connection, final ProtocolFrame frame) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(frame, "frame");
        SecurityContext context = connection.attributes()
                .get(ProductionNetworkConnectionAttributes.SECURITY_CONTEXT)
                .orElse(null);
        if (context == null || context.expired(Instant.now())) {
            return CompletableFuture.completedFuture(NetworkAdmissionDecision.rejected(
                    NetErrorCode.UNAUTHENTICATED, ConnectionRejectionReason.UNAUTHENTICATED));
        }
        if (!context.allows("network.request")) {
            return CompletableFuture.completedFuture(NetworkAdmissionDecision.rejected(
                    NetErrorCode.AUTHORIZATION_DENIED, ConnectionRejectionReason.AUTHORIZATION_DENIED));
        }
        ReplayProtection.ReplayRequest request = new ReplayProtection.ReplayRequest(
                Integer.toUnsignedString(frame.protocolId()), Instant.now(),
                Integer.toUnsignedLong(frame.protocolId()), context.correlationId(),
                Duration.ofSeconds(30));
        return replayProtection.check(request).thenApply(decision -> switch (decision) {
            case ACCEPTED -> NetworkAdmissionDecision.authenticated(context.subject());
            case EXPIRED -> NetworkAdmissionDecision.rejected(
                    NetErrorCode.AUTHENTICATION_EXPIRED, ConnectionRejectionReason.AUTHENTICATION_EXPIRED);
            case REJECTED, INVALID -> NetworkAdmissionDecision.rejected(
                    NetErrorCode.REPLAY_DETECTED, ConnectionRejectionReason.REPLAY_DETECTED);
        });
    }
}
