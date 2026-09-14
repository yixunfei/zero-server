package group.zn.zero.gm;

import java.time.Duration;
import java.time.Instant;

/** Application-owned idempotency state boundary for side-effecting operations. */
public interface GmIdempotencyStore {
    Claim claim(String key, String fingerprint, Instant now, Duration ttl);

    void complete(String key, String fingerprint, GmOperationResponse response, Instant expiresAt);

    void fail(String key, String fingerprint, Instant expiresAt);

    record Claim(State state, GmOperationResponse response) {
        public Claim {
            if (state == null) {
                throw new NullPointerException("state");
            }
            if ((state == State.COMPLETED) != (response != null)) {
                throw new IllegalArgumentException("completed claim must carry response");
            }
        }
    }

    enum State {
        CLAIMED,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CONFLICT
    }
}
