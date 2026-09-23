package group.zn.zero.starter.production;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small server-level admission/drain coordinator. It intentionally does not
 * own GameRuntime and therefore does not alter its lifecycle API.
 */
public final class ServerDrainOrchestrator {
    private final Runnable stopAdmission;
    private final Runnable forceClose;
    private final AtomicReference<ServerDrainState> state = new AtomicReference<>(ServerDrainState.NEW);
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile Instant stateChangedAt = Instant.now();

    public ServerDrainOrchestrator(final Runnable stopAdmission, final Runnable forceClose) {
        this.stopAdmission = Objects.requireNonNull(stopAdmission, "stopAdmission");
        this.forceClose = Objects.requireNonNull(forceClose, "forceClose");
    }

    public ServerDrainState state() {
        return state.get();
    }

    public synchronized void start() {
        if (state.get() == ServerDrainState.NEW) {
            transition(ServerDrainState.RUNNING);
        }
    }

    /** Stops new admission and waits for requests already admitted. */
    public boolean drain(final Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        synchronized (this) {
            ServerDrainState current = state.get();
            if (current == ServerDrainState.STOPPED) return true;
            if (current == ServerDrainState.FAILED) return false;
            if (current != ServerDrainState.DRAINING) {
                transition(ServerDrainState.DRAINING);
                try {
                    stopAdmission.run();
                } catch (RuntimeException | Error failure) {
                    transition(ServerDrainState.FAILED);
                    throw failure;
                }
            }
        }
        boolean drained;
        try {
            drained = awaitZero(timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            drained = false;
        }
        if (drained) {
            synchronized (this) {
                if (state.get() == ServerDrainState.DRAINING) transition(ServerDrainState.STOPPED);
            }
        } else {
            try {
                forceClose.run();
                synchronized (this) { transition(ServerDrainState.STOPPED); }
            } catch (RuntimeException | Error failure) {
                synchronized (this) { transition(ServerDrainState.FAILED); }
                throw failure;
            }
        }
        return drained;
    }

    /** Attempts to admit one request; returns null while not RUNNING. */
    public RequestLease tryAcquire() {
        while (state.get() == ServerDrainState.RUNNING) {
            inFlight.incrementAndGet();
            if (state.get() == ServerDrainState.RUNNING) return new RequestLease();
            inFlight.decrementAndGet();
        }
        return null;
    }

    public ServerHealthSnapshot healthSnapshot() {
        return new ServerHealthSnapshot(state(), inFlight.get(), stateChangedAt);
    }

    private boolean awaitZero(final Duration timeout) throws InterruptedException {
        long nanos = timeout.toNanos();
        synchronized (inFlight) {
            while (inFlight.get() != 0) {
                if (nanos <= 0) return false;
                long before = System.nanoTime();
                TimeUnit.NANOSECONDS.timedWait(inFlight, nanos);
                nanos -= System.nanoTime() - before;
            }
            return true;
        }
    }

    private synchronized void transition(final ServerDrainState next) {
        state.set(next);
        stateChangedAt = Instant.now();
    }

    public final class RequestLease implements AutoCloseable {
        private boolean closed;
        private RequestLease() { }
        @Override public void close() {
            synchronized (inFlight) {
                if (!closed) {
                    closed = true;
                    inFlight.decrementAndGet();
                    inFlight.notifyAll();
                }
            }
        }
    }
}
