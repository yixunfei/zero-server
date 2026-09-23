package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Focused contract tests for the embedded endpoint. */
class PrometheusHttpEndpointTest {
    /** 测试显式管理请求执行器。 */
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** 测试完成后释放执行器。 */
    @org.junit.jupiter.api.AfterEach
    void closeExecutor() {
        executor.shutdownNow();
    }

    /** 非回环需要 token，两个端点对缺失/错误 token 均返回 401。 */
    @Test
    void protectsBothEndpointsAndRejectsUnsafeBind() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PrometheusHttpEndpoint(
                new InetSocketAddress("0.0.0.0", 0), new InMemoryMetricRegistry(), executor));
        try (var endpoint = new PrometheusHttpEndpoint(new InetSocketAddress("127.0.0.1", 0),
                new InMemoryMetricRegistry(), executor, "test-token"); var client = HttpClient.newHttpClient()) {
            endpoint.start();
            for (String path : List.of("/metrics", "/health")) {
                URI uri = URI.create("http://127.0.0.1:" + endpoint.address().getPort() + path);
                assertEquals(401, client.send(HttpRequest.newBuilder(uri).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(401, client.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer wrong")
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(200, client.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer test-token")
                        .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
        }
    }

    /** 阻塞中的抓取不占 acceptor，健康检查可同时完成。 */
    @Test
    void slowScrapeDoesNotBlockHealth() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        MetricRegistry registry = (MetricRegistry) java.lang.reflect.Proxy.newProxyInstance(
                MetricRegistry.class.getClassLoader(), new Class<?>[] {MetricRegistry.class}, (proxy, method, args) -> {
                    if (method.getName().equals("definitions")) {
                        entered.countDown();
                        if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    }
                    return List.of();
                });
        try (var endpoint = new PrometheusHttpEndpoint(new InetSocketAddress("127.0.0.1", 0), registry, executor);
                var client = HttpClient.newHttpClient()) {
            endpoint.start();
            String base = "http://127.0.0.1:" + endpoint.address().getPort();
            var scrape = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var health = client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.get(2, java.util.concurrent.TimeUnit.SECONDS).statusCode());
                assertFalse(scrape.isDone());
            } finally {
                release.countDown();
            }
            assertEquals(200, scrape.get(2, java.util.concurrent.TimeUnit.SECONDS).statusCode());
        }
    }

    @Test
    void lifecycleAndResponsesShouldBeDeterministic() throws Exception {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        registry.register(new MetricDefinition("zero_requests_total", "requests", "count", List.of("result")));
        registry.record(new MetricSample("zero_requests_total", 2D, Map.of("result", "ok"), Instant.parse("2026-08-04T00:00:00Z")));
        PrometheusHttpEndpoint endpoint = new PrometheusHttpEndpoint(new InetSocketAddress("127.0.0.1", 0), registry, executor);

        assertFalse(endpoint.isRunning());
        endpoint.start();
        assertTrue(endpoint.isRunning());
        int port = endpoint.address().getPort();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> metrics = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, metrics.statusCode());
        assertEquals("text/plain; version=0.0.4; charset=utf-8", metrics.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(metrics.body().contains("# TYPE zero_requests_total counter\n"));
        assertTrue(metrics.body().contains("zero_requests_total{result=\"ok\"} 2.0 "));
        assertEquals(200, health.statusCode());
        assertEquals("ok\n", health.body());
        endpoint.start();
        endpoint.stop();
        endpoint.stop();
        assertFalse(endpoint.isRunning());
        assertThrows(IOException.class, () -> client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(), HttpResponse.BodyHandlers.ofString()));
    }

    @Test
    void occupiedPortShouldFailAndClosingShouldReleaseIt() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            int port = socket.getLocalPort();
            PrometheusHttpEndpoint endpoint = new PrometheusHttpEndpoint(new InetSocketAddress("127.0.0.1", port), new InMemoryMetricRegistry(), executor);
            assertThrows(IOException.class, endpoint::start);
            assertFalse(endpoint.isRunning());
        }

        try (PrometheusHttpEndpoint endpoint = new PrometheusHttpEndpoint(new InetSocketAddress("127.0.0.1", 0), new InMemoryMetricRegistry(), executor)) {
            endpoint.start();
            int port = endpoint.address().getPort();
            endpoint.close();
            assertFalse(endpoint.isRunning());
            try (java.net.ServerSocket rebound = new java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                assertEquals(port, rebound.getLocalPort());
            }
        }
    }
}
