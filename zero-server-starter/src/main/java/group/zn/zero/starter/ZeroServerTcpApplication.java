package group.zn.zero.starter;

import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.net.IServer;
import group.zn.zero.runtime.api.GameRuntime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Explicit application lifecycle for a long-running network listener.
 * Runtime and server are injected; this class never creates an executor or listener.
 *
 * @author zn
 */
public final class ZeroServerTcpApplication extends AbstractLifecycle {
    private final GameRuntime runtime;
    private final IServer server;
    private final Supplier<Boolean> probe;

    /** Creates a lifecycle with a read-only probe that reports listener state. */
    public ZeroServerTcpApplication(final GameRuntime runtime, final IServer server) {
        this(runtime, server, () -> server.running());
    }

    /** Creates a lifecycle with an explicitly injected read-only probe. */
    public ZeroServerTcpApplication(final GameRuntime runtime, final IServer server,
            final Supplier<Boolean> probe) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.server = Objects.requireNonNull(server, "server");
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /** Starts runtime first and listener second; listener failure compensates runtime. */
    @Override
    protected void doStart() {
        runtime.start();
        try {
            server.start();
        } catch (RuntimeException failure) {
            RuntimeException cleanupFailure = null;
            try {
                if (runtime.running()) {
                    runtime.stop();
                }
            } catch (RuntimeException cleanup) {
                cleanupFailure = cleanup;
            } finally {
                try {
                    runtime.close();
                } catch (RuntimeException cleanup) {
                    if (cleanupFailure == null) {
                        cleanupFailure = cleanup;
                    } else {
                        cleanupFailure.addSuppressed(cleanup);
                    }
                }
            }
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    protected void beforeStartRequest() {
        if (runtime.running() && !server.running()) {
            throw new IllegalStateException("TCP lifecycle cannot restart a partially started application");
        }
    }

    /** Closes listener and runtime; cleanup is idempotent. */
    public synchronized void close() {
        RuntimeException failure = null;
        try {
            stop();
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            try {
                runtime.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Returns whether the injected listener is running without side effects. */
    public boolean probe() {
        return probe.get();
    }

    /** Returns the current configured or bound address. */
    public String bindAddress() {
        return server.bindAddress();
    }

    /** Returns the injected runtime. */
    public GameRuntime runtime() {
        return runtime;
    }

    /** Returns the injected listener. */
    public IServer server() {
        return server;
    }

    /** Stops listener first and always attempts runtime cleanup. */
    @Override
    protected void doStop() {
        RuntimeException failure = null;
        try {
            server.stop();
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            try {
                runtime.stop();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
