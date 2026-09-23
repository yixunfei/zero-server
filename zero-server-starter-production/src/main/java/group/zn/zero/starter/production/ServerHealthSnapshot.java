package group.zn.zero.starter.production;

import java.time.Instant;
import java.util.Objects;

/** Immutable server health view for readiness/liveness adapters. */
public record ServerHealthSnapshot(ServerDrainState state, int inFlight, Instant capturedAt) {
    public ServerHealthSnapshot {
        state = Objects.requireNonNull(state, "state");
        if (inFlight < 0) throw new IllegalArgumentException("inFlight must not be negative");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    }

    public boolean accepting() {
        return state == ServerDrainState.RUNNING;
    }

    public boolean healthy() {
        return state == ServerDrainState.RUNNING;
    }
}
