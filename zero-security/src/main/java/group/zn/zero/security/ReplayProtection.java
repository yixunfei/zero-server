package group.zn.zero.security;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** Application-supplied replay decision boundary. */
@FunctionalInterface
public interface ReplayProtection {
    CompletionStage<ReplayDecision> check(ReplayRequest request);

    static ReplayProtection failClosed() {
        return request -> java.util.concurrent.CompletableFuture.completedFuture(ReplayDecision.REJECTED);
    }

    static ReplayProtection acceptForTests() {
        return request -> java.util.concurrent.CompletableFuture.completedFuture(ReplayDecision.ACCEPTED);
    }

    record ReplayRequest(String nonce, Instant issuedAt, long sequence, String correlationId, Duration window) {
        public ReplayRequest {
            SecurityValues.require(nonce, "nonce", 256);
            java.util.Objects.requireNonNull(issuedAt, "issuedAt");
            if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
            SecurityValues.require(correlationId, "correlationId", 128);
            java.util.Objects.requireNonNull(window, "window");
            if (window.isNegative() || window.isZero()) throw new IllegalArgumentException("window must be positive");
        }
    }
    enum ReplayDecision { ACCEPTED, REJECTED, EXPIRED, INVALID }
}
