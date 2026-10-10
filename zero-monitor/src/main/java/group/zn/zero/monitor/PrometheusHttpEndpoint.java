package group.zn.zero.monitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Minimal embedded Prometheus HTTP endpoint.
 *
 * <p>请求交给调用方管理的异步执行器；本类不创建线程池。非回环绑定必须配置 Bearer token，
 * token 同时保护 metrics 与 health。TLS 由部署入口提供。</p>
 */
public final class PrometheusHttpEndpoint implements AutoCloseable {
    /** Prometheus scrape path. */
    public static final String METRICS_PATH = "/metrics";
    /** Liveness path. */
    public static final String HEALTH_PATH = "/health";
    /** 业务就绪路径；未提供探针时固定不就绪。 */
    public static final String READY_PATH = "/ready";
    /** 非阻塞状态快照探针，必须线程安全，禁止在探针内连接中间件。 */
    private final BooleanSupplier readiness;
    private static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final String HEALTH_CONTENT_TYPE = "text/plain; charset=utf-8";

    private final InetSocketAddress bindAddress;
    private final MetricRegistry registry;
    private final PrometheusExporter exporter;
    /** 可选 token 的字节快照，不用于诊断输出。 */
    private final byte[] bearerToken;
    private HttpServer server;
    /** 外部管理的非内联执行器，生命周期由装配方负责。 */
    private final Executor executor;

    /**
     * Creates an endpoint with an explicit bind address and registry.
     *
     * @param bindAddress address and port to bind; port 0 is allowed for tests.
     * @param registry registry to scrape; not null.
     * @param executor externally managed asynchronous executor; must not run tasks inline.
     */
    public PrometheusHttpEndpoint(
            final InetSocketAddress bindAddress, final MetricRegistry registry, final Executor executor) {
        this(bindAddress, registry, executor, null);
    }

    /**
     * 创建带鉴权的端点；构造不启动资源，start/stop 线程安全。
     * @param bindAddress 监听地址；非回环必须配置 token。
     * @param registry 指标表；不可为空。
     * @param executor 外部管理的异步执行器；不得内联运行，端点不负责关闭。
     * @param bearerToken token；仅回环监听可以为空。
     * @throws IllegalArgumentException token 为空白或非回环缺少 token 时抛出。
     */
    public PrometheusHttpEndpoint(
            final InetSocketAddress bindAddress,
            final MetricRegistry registry,
            final Executor executor,
            final String bearerToken) {
        this(bindAddress, registry, executor, bearerToken, () -> false);
    }

    /**
     * 创建带业务就绪探针的端点；/health 仅表明端点存活，/ready 表明调用方允许接流量。
     * @param bindAddress 监听地址。 @param registry 指标表。 @param executor 受管异步执行器。
     * @param bearerToken 非回环必须提供。 @param readiness 非阻塞线程安全探针，停服排空时应返回 false。
     * @throws NullPointerException 必需依赖为空。 @throws IllegalArgumentException 无效 token 或不安全绑定。
     */
    public PrometheusHttpEndpoint(final InetSocketAddress bindAddress, final MetricRegistry registry,
            final Executor executor, final String bearerToken, final BooleanSupplier readiness) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.exporter = new PrometheusExporter();
        this.executor = Objects.requireNonNull(executor, "executor");
        if (bearerToken != null && bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken must not be blank");
        }
        if (bearerToken == null && !isLoopback(bindAddress)) {
            throw new IllegalArgumentException("bearerToken is required for non-loopback bind");
        }
        this.bearerToken = bearerToken == null ? null : bearerToken.getBytes(StandardCharsets.UTF_8);
    }

    /** Starts the endpoint, binding before returning. Repeated starts are idempotent. */
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        HttpServer created = HttpServer.create(bindAddress, 0);
        created.createContext(METRICS_PATH, this::handleMetrics);
        created.createContext(HEALTH_PATH, this::handleHealth);
        created.createContext(READY_PATH, this::handleReady);
        created.setExecutor(executor);
        try {
            created.start();
            server = created;
        } catch (RuntimeException | Error failure) {
            created.stop(0);
            throw failure;
        }
    }

    /** Stops the endpoint immediately. Repeated stops are idempotent. */
    public synchronized void stop() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            current.stop(0);
        }
    }

    /** Returns whether the endpoint is currently started. */
    public synchronized boolean isRunning() {
        return server != null;
    }

    /** Returns the configured bind address (port 0 is not replaced by the effective port). */
    public InetSocketAddress bindAddress() {
        return bindAddress;
    }

    /** Returns the effective bound address after start, or the configured address before start. */
    public synchronized InetSocketAddress address() {
        return server == null ? bindAddress : server.getAddress();
    }

    @Override
    public void close() {
        stop();
    }

    private void handleMetrics(final HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!METRICS_PATH.equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, "not found\n", HEALTH_CONTENT_TYPE);
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "method not allowed\n", HEALTH_CONTENT_TYPE);
            return;
        }
        String body;
        try { body = exporter.export(registry); }
        catch (RuntimeException failure) {
            send(exchange, 500, MonitorErrorCode.METRIC_SAMPLE_INVALID.code() + "\n", HEALTH_CONTENT_TYPE);
            return;
        }
        send(exchange, 200, body, PROMETHEUS_CONTENT_TYPE);
    }

    private void handleHealth(final HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        if (!HEALTH_PATH.equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, "not found\n", HEALTH_CONTENT_TYPE);
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "method not allowed\n", HEALTH_CONTENT_TYPE);
            return;
        }
        send(exchange, 200, "ok\n", HEALTH_CONTENT_TYPE);
    }

    private void handleReady(final HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) return;
        if (!READY_PATH.equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, "not found\n", HEALTH_CONTENT_TYPE);
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "method not allowed\n", HEALTH_CONTENT_TYPE);
            return;
        }
        try {
            boolean ready = readiness.getAsBoolean();
            send(exchange, ready ? 200 : 503, ready ? "ready\n" : "not ready\n", HEALTH_CONTENT_TYPE);
        } catch (RuntimeException failure) {
            // 不泄露探针的自由异常文本，明确失败关闭；稳定错误码便于排障。
            send(exchange, 503, MonitorErrorCode.SYSTEM_PROBE_FAILED.code() + "\n", HEALTH_CONTENT_TYPE);
        }
    }

    private void send(
            final HttpExchange exchange,
            final int status,
            final String body,
            final String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private boolean authorized(final HttpExchange exchange) throws IOException {
        if (bearerToken == null) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String prefix = "Bearer ";
        if (authorization == null || !authorization.startsWith(prefix)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            send(exchange, 401, "unauthorized\n", HEALTH_CONTENT_TYPE);
            return false;
        }
        byte[] supplied = authorization.substring(prefix.length()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(bearerToken, supplied)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            send(exchange, 401, "unauthorized\n", HEALTH_CONTENT_TYPE);
            return false;
        }
        return true;
    }

    private boolean isLoopback(final InetSocketAddress address) {
        java.net.InetAddress resolved = address.getAddress();
        return resolved != null && resolved.isLoopbackAddress();
    }
}
