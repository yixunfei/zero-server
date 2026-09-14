package group.zn.zero.net.lifecycle;

import group.zn.zero.net.IConnection;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.security.AuthenticationProvider;
import group.zn.zero.security.ReplayProtection;
import group.zn.zero.security.SecurityChain;
import group.zn.zero.security.SecurityContext;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Adapts the transport-neutral security chain to the production network policy.
 * Credential material is reduced to a digest before it crosses the policy boundary.
 */
public final class SecurityNetworkPolicy implements ProductionNetworkPolicy {
    private final ProductionNetworkPolicy delegate;
    private final SecurityChain security;

    public SecurityNetworkPolicy(final ProductionNetworkPolicy delegate, final SecurityChain security) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.security = Objects.requireNonNull(security, "security");
    }

    @Override
    public NetworkAdmissionDecision validateHandshake(
            final IConnection connection, final ProtocolFrame handshakeFrame) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(handshakeFrame, "handshakeFrame");
        if (security.tlsRequired()
                && !connection.attributes().get(ProductionNetworkConnectionAttributes.TLS_ESTABLISHED)
                .orElse(false)) {
            return NetworkAdmissionDecision.rejected(
                    NetErrorCode.TLS_REQUIRED,
                    ConnectionRejectionReason.TLS_REQUIRED);
        }
        return delegate.validateHandshake(connection, handshakeFrame);
    }

    @Override
    public CompletionStage<NetworkAdmissionDecision> authenticate(
            final IConnection connection, final ProtocolFrame handshakeFrame) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(handshakeFrame, "handshakeFrame");
        AuthenticationProvider.AuthenticationRequest request = new AuthenticationProvider.AuthenticationRequest(
                digest(handshakeFrame.payload()),
                "tcp",
                String.valueOf(connection.remoteAddress()),
                "network-security",
                Instant.now());
        return security.authentication().authenticate(request)
                .thenApply(result -> {
                    if (result == null || !result.accepted()) {
                        NetErrorCode code = result != null
                                && result.failure() == AuthenticationProvider.AuthenticationFailure.EXPIRED
                                ? NetErrorCode.AUTHENTICATION_EXPIRED
                                : NetErrorCode.AUTHENTICATION_REJECTED;
                        ConnectionRejectionReason reason = result != null
                                && result.failure() == AuthenticationProvider.AuthenticationFailure.EXPIRED
                                ? ConnectionRejectionReason.AUTHENTICATION_EXPIRED
                                : ConnectionRejectionReason.AUTHENTICATION_REJECTED;
                        return NetworkAdmissionDecision.rejected(code, reason);
                    }
                    connection.attributes().put(
                            ProductionNetworkConnectionAttributes.SECURITY_CONTEXT,
                            result.context());
                    return NetworkAdmissionDecision.authenticated(result.context().subject());
                });
    }

    /**
     * Asynchronously checks request replay state without blocking the Netty EventLoop.
     *
     * @param frame inbound frame; non-null.
     * @param context authenticated security context; non-null.
     * @return replay decision stage; non-null and fail-closed on null provider results.
     */
    public CompletionStage<ReplayProtection.ReplayDecision> checkReplayAsync(
            final ProtocolFrame frame,
            final SecurityContext context) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(context, "context");
        ReplayProtection.ReplayRequest request = new ReplayProtection.ReplayRequest(
                digest(frame.extension()),
                Instant.now(),
                Integer.toUnsignedLong(frame.protocolId()),
                context.correlationId(),
                Duration.ofSeconds(30));
        CompletionStage<ReplayProtection.ReplayDecision> stage;
        try {
            stage = security.replayProtection().check(request);
        } catch (RuntimeException exception) {
            return java.util.concurrent.CompletableFuture.failedStage(exception);
        }
        if (stage == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    ReplayProtection.ReplayDecision.INVALID);
        }
        return stage.thenApply(decision -> decision == null
                ? ReplayProtection.ReplayDecision.INVALID
                : decision);
    }

    @Override
    public boolean isHeartbeat(final IConnection connection, final ProtocolFrame frame) {
        return delegate.isHeartbeat(connection, frame);
    }

    @Override
    public CompletionStage<Void> coordinateReconnect(
            final IConnection connection, final String subjectId, final Duration reconnectWindow) {
        return delegate.coordinateReconnect(connection, subjectId, reconnectWindow);
    }

    private static String digest(final byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
