package __PACKAGE__;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local scaffold observation facade. It is deliberately side-effect free apart from counters.
 * Business handlers may use it from any framework-managed execution domain.
 *
 * @author zn
 */
public final class LocalGameObservation {

    /**
     * Shared business command counter name.
     */
    private static final String COMMAND_METRIC = "local_game_command_total";

    /**
     * No-op facade used when a caller only needs the per-command observer.
     */
    static final LocalGameObservation NO_OP_OBSERVER = new LocalGameObservation();

    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    /** Creates an empty observation facade. */
    public LocalGameObservation() {
    }

    /**
     * Returns the shared command counter metric name.
     *
     * <p>The smoke flow, the TCP entry point and business code record the same metric so one
     * dashboard covers both entry points. The name is stable and low cardinality; the
     * {@code action} label carries the business operation.</p>
     *
     * @return metric name; never null; thread-safe.
     */
    public static String commandMetric() {
        return COMMAND_METRIC;
    }

    /** Records an asynchronously completed operation. */
    public void completed() {
        completed.incrementAndGet();
    }

    /** Records an asynchronously failed operation. */
    public void failed(final Throwable error) {
        Objects.requireNonNull(error, "error");
        failed.incrementAndGet();
    }

    /** Returns the completed operation count. */
    public int completedCount() {
        return completed.getPlain();
    }

    /** Returns the failed operation count. */
    public int failedCount() {
        return failed.getPlain();
    }
}
