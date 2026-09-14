package group.zn.zero.runtime.production;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe, fail-closed state machine for periodic adapter resilience. */
public final class RuntimeAdapterStateMachine {
    private final AtomicReference<Snapshot> current;

    public RuntimeAdapterStateMachine(final String adapterName) {
        String name = Objects.requireNonNull(adapterName, "adapterName");
        if (name.isBlank()) {
            throw new IllegalArgumentException("adapterName must not be blank");
        }
        current = new AtomicReference<>(new Snapshot(name, State.READY, Instant.now()));
    }

    public Snapshot snapshot() {
        return current.get();
    }

    public Snapshot transition(final State next) {
        Objects.requireNonNull(next, "next");
        return current.updateAndGet(previous -> {
            if (!allowed(previous.state(), next)) {
                throw new IllegalStateException(
                        "invalid adapter state transition: " + previous.state() + " -> " + next);
            }
            return new Snapshot(previous.adapterName(), next, Instant.now());
        });
    }

    private static boolean allowed(final State from, final State to) {
        return switch (from) {
            case READY -> to == State.DEGRADED || to == State.FAILED
                    || to == State.DRAIN || to == State.STOP;
            case DEGRADED -> to == State.RECOVERING || to == State.FAILED || to == State.DRAIN;
            case RECOVERING -> to == State.READY || to == State.FAILED || to == State.DRAIN;
            case FAILED -> to == State.DRAIN || to == State.STOP;
            case DRAIN -> to == State.STOP;
            case STOP -> to == State.CLOSE;
            case CLOSE -> false;
        };
    }

    public enum State {
        READY,
        DEGRADED,
        RECOVERING,
        FAILED,
        DRAIN,
        STOP,
        CLOSE
    }

    public record Snapshot(String adapterName, State state, Instant observedAt) {
        public Snapshot {
            Objects.requireNonNull(adapterName, "adapterName");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }
}
