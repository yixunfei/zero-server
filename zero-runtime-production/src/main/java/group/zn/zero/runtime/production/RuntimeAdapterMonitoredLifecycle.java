package group.zn.zero.runtime.production;

import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.core.lifecycle.Lifecycle;
import java.util.Objects;

/** Starts an adapter with its runtime monitor and closes admission before the adapter stops. */
public final class RuntimeAdapterMonitoredLifecycle extends AbstractLifecycle {
    private final Lifecycle adapter;
    private final RuntimeAdapterIo monitor;

    /** Creates a lifecycle for one adapter and its operation monitor. */
    public RuntimeAdapterMonitoredLifecycle(final Lifecycle adapter, final RuntimeAdapterIo monitor) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
    }

    @Override protected void doStart() {
        adapter.start();
        try {
            monitor.start();
        } catch (RuntimeException | Error failure) {
            try { adapter.stop(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override protected void doStop() {
        Throwable failure = null;
        try { monitor.stop(); }
        catch (RuntimeException | Error current) { failure = current; }
        try { adapter.stop(); }
        catch (RuntimeException | Error current) {
            if (failure == null) failure = current;
            else failure.addSuppressed(current);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }
}
