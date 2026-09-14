package group.zn.zero.security;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** Application-supplied authentication boundary; no account or token implementation is included. */
@FunctionalInterface
public interface AuthenticationProvider {
    CompletionStage<AuthenticationResult> authenticate(AuthenticationRequest request);

    /** A safe default for production composition. */
    static AuthenticationProvider failClosed() {
        return request -> java.util.concurrent.CompletableFuture.completedFuture(AuthenticationResult.rejected());
    }

    record AuthenticationRequest(
            String credentialReference,
            String transport,
            String peerAddress,
            String traceId,
            Instant receivedAt) {
        public AuthenticationRequest {
            SecurityValues.require(credentialReference, "credentialReference", 4096);
            SecurityValues.require(transport, "transport", 32);
            SecurityValues.require(peerAddress, "peerAddress", 128);
            SecurityValues.require(traceId, "traceId", 128);
            java.util.Objects.requireNonNull(receivedAt, "receivedAt");
        }
    }

    record AuthenticationResult(SecurityContext context, AuthenticationFailure failure) {
        public AuthenticationResult {
            if ((context == null) == (failure == null)) {
                throw new IllegalArgumentException("exactly one authentication result must be present");
            }
        }
        public static AuthenticationResult accepted(SecurityContext context) {
            return new AuthenticationResult(java.util.Objects.requireNonNull(context, "context"), null);
        }
        public static AuthenticationResult rejected() {
            return new AuthenticationResult(null, AuthenticationFailure.REJECTED);
        }
        public static AuthenticationResult expired() {
            return new AuthenticationResult(null, AuthenticationFailure.EXPIRED);
        }
        public boolean accepted() { return context != null; }
    }

    enum AuthenticationFailure { REJECTED, EXPIRED, MALFORMED }
}
