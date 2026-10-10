package __PACKAGE__;

import __PACKAGE__.generated.protocol.ProtocolIds;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.config.ZeroConfigLoader;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.IServer;
import group.zn.zero.net.ServerFactory;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.starter.ZeroServerTcpApplication;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Long-running TCP entry point for this local/prototype scaffold.
 *
 * <pre>
 * command line / operator
 *   -> LocalGameServer.start(config)
 *   -> RuntimeAssembly or the local runtime facade (GameRuntime)
 *   -> zero-net listener with the zero binary frame codec
 *   -> GeneratedProtocolDispatcher
 *   -> LocalGameBO / handwritten XXXEventBOImp
 *   -> Actor lanes, log appender and monitor registry
 * </pre>
 *
 * <p>This class is the network-to-business boundary of the scaffold. Business work stays inside
 * the generated BO implementations; the listener itself never contains game rules. The
 * generated dispatcher is synchronous today, so one decoded frame equals one dispatch call,
 * while business services keep returning {@code CompletionStage} on their own Actor lanes.</p>
 *
 * <p>Boundaries: no authentication, TLS, handshake, heartbeat, rate limiting, backpressure,
 * capacity or long-stability guarantee. Use {@code --once} only for a local smoke check.</p>
 *
 * @author zn
 */
public final class __SERVER_CLASS__ {

    /**
     * Default listener host; overridden by {@code zero.net.host}.
     */
    private static final String DEFAULT_HOST = "127.0.0.1";

    /**
     * Default listener port; overridden by {@code zero.net.port}; {@code 0} binds an ephemeral port.
     */
    private static final int DEFAULT_PORT = 6200;

    /**
     * Default maximum accepted frame length; overridden by {@code zero.net.maxFrameLength}.
     */
    private static final int DEFAULT_MAX_FRAME_LENGTH = ServerOptions.DEFAULT_MAX_FRAME_LENGTH;

    /** Listener host configuration key. */
    private static final String HOST_KEY = "zero.net.host";

    /** Listener port configuration key. */
    private static final String PORT_KEY = "zero.net.port";

    /** Maximum frame length configuration key. */
    private static final String MAX_FRAME_KEY = "zero.net.maxFrameLength";

    /**
     * Stable log source for this entry point.
     */
    private static final LogSource LOG_SOURCE = new LogSource("__PROJECT_NAME__", "__RUNTIME_PROFILE__", "server");

    /**
     * Business thread name; the executor is created and owned by this class.
     */
    private static final String LOGIC_THREAD_NAME = "__PROJECT_NAME__-logic";

    private __SERVER_CLASS__() {
    }

    /**
     * Command line entry point.
     *
     * @param args command line arguments; {@code --port=N} overrides the configured port and
     *             {@code --once} sends one smoke request and exits; may be empty.
     * @throws Exception when the listener cannot start, the smoke request fails or shutdown fails.
     */
    public static void main(final String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Map<String, String> overrides = new HashMap<>();
        arguments.get("host").ifPresent(host -> overrides.put(HOST_KEY, host));
        arguments.get("port").ifPresent(port -> overrides.put(PORT_KEY, port));
        try (Server server = start(overrides(overrides))) {
            LogAppender appender = server.runtime().require(LogRuntime.LOG_APPENDER);
            appender.append(log("listen", "tcp listener ready", "listen"));
            System.out.println("tcp-server=ready|address=" + server.bindAddress()
                    + "|maxProtocolId=" + ProtocolIds.MAX_ID);
            if (arguments.has("once")) {
                __CLIENT_CLASS__.SmokeResult result = __CLIENT_CLASS__.smoke(
                        server.bindAddress(), server.runtime());
                System.out.println(result.summaryLine());
            } else {
                LocalGameLifecycle.await(server, server.bindAddress());
            }
        }
    }

    /**
     * Starts this scaffold as one long-running TCP composition.
     *
     * <p>The returned server is single-use and owns the runtime, the business services, the
     * listener and the executors created here. Construction does not bind anything;
     * {@link Server#start()} starts the runtime before the listener, and
     * {@link Server#close()} stops the listener before the runtime and always shuts the
     * executors down.</p>
     *
     * @param config framework configuration; never null.
     * @return composition facade; never null; owns all created resources.
     * @throws RuntimeException when the listener cannot bind or the runtime cannot start.
     */
    public static Server start(final ZeroConfig config) {
        Server server = new Server(config);
        server.start();
        return server;
    }

    /**
     * Loads the default scaffold configuration with optional key overrides.
     *
     * @param overrides extra standard configuration keys; never null; may be empty.
     * @return configuration snapshot; never null; immutable; thread-safe.
     */
    public static ZeroConfig overrides(final Map<String, String> overrides) {
        Objects.requireNonNull(overrides, "overrides");
        Map<String, String> values = new HashMap<>();
        values.putAll(Map.ofEntries(__CONFIG_DEFAULTS__));
        values.put(ZeroRuntimeConfigKeys.ZERO_NAME, "__PROJECT_NAME__");
        values.put(HOST_KEY, DEFAULT_HOST);
        values.put(PORT_KEY, Integer.toString(DEFAULT_PORT));
        values.put(MAX_FRAME_KEY, Integer.toString(DEFAULT_MAX_FRAME_LENGTH));
        values.putAll(ZeroConfigLoader.loadStandard(values).asMap());
        values.putAll(overrides);
        return new group.zn.zero.core.config.MapZeroConfig(values);
    }

    private static ZeroLogRecord log(final String operation, final String message, final String action) {
        return ZeroLogRecord.create(Instant.now(), LogLevel.INFO, LogType.RUNTIME, LOG_SOURCE,
                new LogOperation(operation, LogResult.SUCCESS, null), "trace-tcp-" + operation, message,
                Map.of("action", action));
    }

    /**
     * Long-running TCP composition of this scaffold.
     *
     * <p>Thread safety: start/close follow the framework lifecycle lock; {@code handledRequests}
     * and {@code failedRequests} are atomic counters. Business state stays inside the injected
     * services and their Actor lanes; this class holds no mutable game state.</p>
     *
     * @author zn
     */
    public static final class Server implements AutoCloseable {

        /**
         * Listener host configuration key.
         */
        private static final String HOST_KEY = "zero.net.host";

        /**
         * Listener port configuration key.
         */
        private static final String PORT_KEY = "zero.net.port";

        /**
         * Maximum frame length configuration key.
         */
        private static final String MAX_FRAME_KEY = "zero.net.maxFrameLength";

        /** 本地业务服务资源，构造失败时也必须关闭。 */
        private LocalGameFixture fixture;

        /** Executors created and owned by this composition. */
        private final ZeroRuntimeExecutors executors;

        /**
         * Business runtime graph.
         */
        private GameRuntime runtime;

        /**
         * Generated dispatcher bound to the handwritten BO.
         */
        private final LocalGameFlow flow;

        /**
         * Network listener owned by this composition.
         */
        private final IServer listener;
        /** 复用框架启停事务。 */
        private final ZeroServerTcpApplication application;

        /** 受同步生命周期方法保护，避免主线程和 shutdown hook 重复释放。 */
        private boolean closed;
        /**
         * Handled frame counter.
         */
        private final AtomicInteger handled = new AtomicInteger();

        /**
         * Dispatch failure counter.
         */
        private final AtomicInteger dispatchFailures = new AtomicInteger();

        private Server(final ZeroConfig config) {
            Objects.requireNonNull(config, "config");
            String host = config.get(HOST_KEY).orElse(DEFAULT_HOST);
            if (!host.equals("127.0.0.1") && !host.equals("::1")) {
                throw new IllegalArgumentException("local prototype accepts only literal loopback addresses");
            }
            this.executors = ZeroRuntimeExecutors.singleThreaded(LOGIC_THREAD_NAME);
            try {
                this.runtime = LocalGameFlow.createRuntime(config, LocalGameObservation.consoleAppender());
                MonitorRuntime monitor = runtime.require(MonitorRuntimeComponent.MONITOR_RUNTIME);

                this.fixture = new LocalGameFixture(
                        runtime.require(ActorRuntime.ACTOR_SCHEDULER), uid -> "profile-" + uid);
                this.flow = new LocalGameFlow(fixture.playerService(), fixture.sceneService(),
                        new LocalGameObservation(runtime.require(LogRuntime.LOG_APPENDER), monitor, LOG_SOURCE));
                this.listener = listener(config, flow);
                this.application = new ZeroServerTcpApplication(runtime, listener);

            } catch (RuntimeException failure) {
                try { if (fixture != null) fixture.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                try { if (runtime != null) runtime.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                try { executors.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        private IServer listener(final ZeroConfig config, final LocalGameFlow generated) {
            String host = config.get(HOST_KEY).orElse(DEFAULT_HOST);
            if (!host.equals("127.0.0.1") && !host.equals("::1")) {
                throw new IllegalArgumentException("local prototype accepts only literal loopback addresses");
            }
            int port = config.get(PORT_KEY).map(Integer::parseInt).orElse(DEFAULT_PORT);
            int maxFrameLength = config.get(MAX_FRAME_KEY).map(Integer::parseInt)
                    .orElse(DEFAULT_MAX_FRAME_LENGTH);
            Executor executor = executors.logicExecutor();
            return ServerFactory.tcpUnmanaged(
                    ServerOptions.tcp(host, port).withIoThreads(1, 1).withMaxFrameLength(maxFrameLength),
                    new ZeroBinaryFrameCodec(),
                    frameHandler(generated),
                    new ConnectionListener() {
                        @Override
                        public void onClose(final IConnection connection) {
                            // Connections are stateless in this scaffold. Session cleanup,
                            // reconnect windows and player binding belong to application code.
                        }
                    },
                    executor);
        }

        private ServerFrameHandler frameHandler(final LocalGameFlow generated) {
            return (connection, frame) -> {
                handled.incrementAndGet();
                return generated.dispatch(frame.protocolId(), frame.payload()).handle((value, failure) -> {
                    if (failure != null) {
                        dispatchFailures.incrementAndGet();
                        return List.of(errorFrame(frame, LocalGameObservation.error(failure).code()));
                    }
                    // 演示回包在业务真正完成后发出；协议仍是既有 DTO 回显。
                    return List.of(frame);
                });
            };
        }

        private static ProtocolFrame errorFrame(final ProtocolFrame request, final String reason) {
            return group.zn.zero.protocol.ProtocolErrorFrames.response(request, reason);
        }

        /**
         * Returns the runtime owned by this composition.
         *
         * @return runtime; never null; thread-safe.
         */
        public GameRuntime runtime() {
            return runtime;
        }

        /**
         * Returns the bound listener address, including the resolved ephemeral port.
         *
         * @return {@code host:port}; never null; thread-safe.
         */
        public String bindAddress() {
            return application.bindAddress();
        }

        /**
         * Returns whether the listener currently accepts connections.
         *
         * @return true while listening; thread-safe; read-only probe without side effects.
         */
        public boolean running() {
            return runtime.running() && application.probe();
        }

        /**
         * Returns the runtime mode read from configuration.
         *
         * @return mode value; never null; thread-safe.
         */
        public String mode() {
            return runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_MODE, "unknown");
        }

        /**
         * Returns the runtime name read from configuration.
         *
         * @return name value; never null; thread-safe.
         */
        public String name() {
            return runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, "unknown");
        }

        /**
         * Returns how many frames reached the dispatcher.
         *
         * @return frame count; never negative; thread-safe.
         */
        public int handledRequests() {
            return handled.get();
        }

        /**
         * Returns how many frames failed dispatch, including unknown protocols.
         *
         * @return failure count; never negative; thread-safe.
         */
        public int dispatchFailures() {
            return dispatchFailures.get();
        }

        /**
         * Starts the runtime and then the listener.
         *
         * @throws RuntimeException when the runtime or the listener fails to start; the runtime
         *                          is compensated and closed before the failure is rethrown.
         */
        public synchronized void start() {
            if (closed) throw new IllegalStateException("server already closed");
            try {
                application.start();
            } catch (RuntimeException failure) {
                try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        /**
         * Stops the listener, the runtime and the executors owned by this class.
         *
         * <p>Idempotent: subsequent calls do not release resources again. Exceptions from the
         * listener and runtime are rethrown after executors are released.</p>
         */
        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            try { application.close(); } catch (RuntimeException cleanup) { failure = cleanup; }
            try { fixture.close(); } catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
            try { executors.close(); } catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
            if (failure != null) throw failure;
        }
    }

    /**
     * Parsed command line options.
     *
     * @param values option name to value; never null; immutable; thread-safe.
     * @author zn
     */
    private record Arguments(Map<String, String> values) {

        /**
         * Parses {@code --key=value} and bare {@code --flag} arguments.
         *
         * @param args raw arguments; may be null.
         * @return parsed options; never null; immutable.
         */
        private static Arguments parse(final String[] args) {
            Map<String, String> values = new HashMap<>();
            if (args != null) {
                for (String argument : args) {
                    if (argument == null || !argument.startsWith("--")) {
                        continue;
                    }
                    int separator = argument.indexOf('=');
                    if (separator < 0) {
                        values.put(argument.substring(2).toLowerCase(Locale.ROOT), "true");
                    } else {
                        values.put(argument.substring(2, separator).toLowerCase(Locale.ROOT),
                                argument.substring(separator + 1));
                    }
                }
            }
            return new Arguments(Map.copyOf(values));
        }

        /**
         * Returns whether a flag is present.
         *
         * @param name flag name without leading dashes.
         * @return true when present.
         */
        private boolean has(final String name) {
            return values.containsKey(name);
        }

        /**
         * Returns a configured value.
         *
         * @param name option name without leading dashes.
         * @return value; empty when absent; never null.
         */
        private Optional<String> get(final String name) {
            return Optional.ofNullable(values.get(name));
        }
    }
}
