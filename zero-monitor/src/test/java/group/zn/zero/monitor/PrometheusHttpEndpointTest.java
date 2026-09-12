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

    @Test
    void lifecycleAndResponsesShouldBeDeterministic() throws Exception {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        registry.register(new MetricDefinition("zero_requests_total", "requests", "count", List.of("result")));
        registry.record(new MetricSample("zero_requests_total", 2D, Map.of("result", "ok"), Instant.parse("2026-08-04T00:00:00Z")));
        PrometheusHttpEndpoint endpoint = new PrometheusHttpEndpoint(new InetSocketAddress("127.0.0.1", 0), registry);

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
            PrometheusHttpEndpoint endpoint = new PrometheusHttpEndpoint(new InetSocketAddress("127.0.0.1", port), new InMemoryMetricRegistry());
            assertThrows(IOException.class, endpoint::start);
            assertFalse(endpoint.isRunning());
        }

        try (PrometheusHttpEndpoint endpoint = new PrometheusHttpEndpoint(new InetSocketAddress("127.0.0.1", 0), new InMemoryMetricRegistry())) {
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
