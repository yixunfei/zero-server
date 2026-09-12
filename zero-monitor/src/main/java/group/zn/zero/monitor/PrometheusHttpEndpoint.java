package group.zn.zero.monitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Minimal embedded Prometheus HTTP endpoint.
 *
 * <p>This endpoint owns only the explicitly bound JDK HTTP server. Requests are
 * handled synchronously through an explicit executor and no application
 * thread pool is created. Authentication, TLS, rate limiting and proxy
 * policy are intentionally outside this minimum slice.</p>
 */
public final class PrometheusHttpEndpoint implements AutoCloseable {
    /** Prometheus scrape path. */
    public static final String METRICS_PATH = "/metrics";
    /** Liveness path. */
    public static final String HEALTH_PATH = "/health";
    private static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";
    private static final String HEALTH_CONTENT_TYPE = "text/plain; charset=utf-8";

    private final InetSocketAddress bindAddress;
    private final MetricRegistry registry;
    private final PrometheusExporter exporter;
    private HttpServer server;

    /**
     * Creates an endpoint with an explicit bind address and registry.
     *
     * @param bindAddress address and port to bind; port 0 is allowed for tests.
     * @param registry registry to scrape; not null.
     */
    public PrometheusHttpEndpoint(final InetSocketAddress bindAddress, final MetricRegistry registry) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.exporter = new PrometheusExporter();
    }

    /** Starts the endpoint, binding before returning. Repeated starts are idempotent. */
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        HttpServer created = HttpServer.create(bindAddress, 0);
        created.createContext(METRICS_PATH, this::handleMetrics);
        created.createContext(HEALTH_PATH, this::handleHealth);
        // Do not let the JDK install an implicit executor/thread pool.
        created.setExecutor(Runnable::run);
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
        if (!METRICS_PATH.equals(exchange.getRequestURI().getPath())) {
            send(exchange, 404, "not found\n", HEALTH_CONTENT_TYPE);
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "method not allowed\n", HEALTH_CONTENT_TYPE);
            return;
        }
        send(exchange, 200, exporter.export(registry), PROMETHEUS_CONTENT_TYPE);
    }

    private void handleHealth(final HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "method not allowed\n", HEALTH_CONTENT_TYPE);
            return;
        }
        send(exchange, 200, "ok\n", HEALTH_CONTENT_TYPE);
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
}
