package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.observer.RpcTransportEvent;
import group.zn.zero.rpc.observer.RpcTransportEventType;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Kafka RPC pending 请求表测试。
 *
 * @author zn
 */
class KafkaRpcPendingRequestsTest {

    /**
     * 验证时间轮超时会失败 pending 请求并释放容量。
     */
    @Test
    void timeWheelShouldTimeoutPendingRequestAndReleaseCapacity() {
        try (KafkaRpcPendingRequests pending = new KafkaRpcPendingRequests(1, Duration.ofMillis(5), 8)) {
            CompletableFuture<RpcResponse> first = pending.register(request("corr-timeout", 30));

            waitUntil(first::isDone);

            CompletionException exception = assertThrows(CompletionException.class, first::join);
            ZeroException cause = assertInstanceOf(ZeroException.class, exception.getCause());
            assertEquals(RpcErrorCode.REQUEST_TIMEOUT.code(), cause.errorCode().code());
            assertEquals(0, pending.size());

            CompletableFuture<RpcResponse> second = pending.register(request("corr-after-timeout", 200));
            assertFalse(second.isDone());
            assertTrue(pending.complete(response("corr-after-timeout")));
            assertEquals(SystemErrorCode.OK.code(), second.join().errorCode().code());
        }
    }

    /**
     * 验证 pending 超时观测事件保留 traceId 和路由信息，便于生产排障。
     */
    @Test
    void pendingTimeoutEventShouldKeepTraceAndRoute() {
        CopyOnWriteArrayList<RpcTransportEvent> events = new CopyOnWriteArrayList<>();
        try (KafkaRpcPendingRequests pending = new KafkaRpcPendingRequests(
                1,
                Duration.ofMillis(5),
                8,
                events::add,
                "kafka")) {
            CompletableFuture<RpcResponse> first = pending.register(request("corr-observed-timeout", 30));

            waitUntil(first::isDone);

            CompletionException exception = assertThrows(CompletionException.class, first::join);
            ZeroException cause = assertInstanceOf(ZeroException.class, exception.getCause());
            assertEquals(RpcErrorCode.REQUEST_TIMEOUT.code(), cause.errorCode().code());
        }

        RpcTransportEvent timeout = events.stream()
                .filter(event -> event.type() == RpcTransportEventType.PENDING_TIMED_OUT)
                .findFirst()
                .orElseThrow();
        assertEquals("corr-observed-timeout", timeout.correlationId());
        assertEquals("trace", timeout.traceId());
        assertEquals("service", timeout.serviceName());
        assertEquals("method", timeout.methodName());
    }

    /**
     * 验证请求在超时前完成后，旧时间轮任务不会误伤已完成 future。
     */
    @Test
    void completionBeforeTimeoutShouldIgnoreStaleWheelTask() {
        try (KafkaRpcPendingRequests pending = new KafkaRpcPendingRequests(1, Duration.ofMillis(5), 8)) {
            CompletableFuture<RpcResponse> first = pending.register(request("corr-complete", 40));

            assertTrue(pending.complete(response("corr-complete")));
            assertEquals(SystemErrorCode.OK.code(), first.join().errorCode().code());
            assertEquals(0, pending.size());

            sleep(90);

            assertTrue(first.isDone());
            assertEquals(SystemErrorCode.OK.code(), first.join().errorCode().code());
            CompletableFuture<RpcResponse> second = pending.register(request("corr-after-complete", 200));
            assertFalse(second.isDone());
            assertTrue(pending.complete(response("corr-after-complete")));
        }
    }

    /**
     * 验证复用 correlationId 时，旧时间轮任务不会误超时后一个 pending 请求。
     */
    @Test
    void staleWheelTaskShouldNotTimeoutReusedCorrelationId() {
        try (KafkaRpcPendingRequests pending = new KafkaRpcPendingRequests(1, Duration.ofMillis(5), 8)) {
            CompletableFuture<RpcResponse> first = pending.register(request("corr-reuse", 35));

            assertTrue(pending.complete(response("corr-reuse")));
            assertEquals(SystemErrorCode.OK.code(), first.join().errorCode().code());

            CompletableFuture<RpcResponse> second = pending.register(request("corr-reuse", 250));
            sleep(90);

            assertFalse(second.isDone());
            assertTrue(pending.complete(response("corr-reuse")));
            assertEquals(SystemErrorCode.OK.code(), second.join().errorCode().code());
        }
    }

    /**
     * 验证关闭 pending 表会失败所有未完成请求，并拒绝新注册。
     */
    @Test
    void closeShouldFailPendingAndRejectNewRegistration() {
        KafkaRpcPendingRequests pending = new KafkaRpcPendingRequests(1, Duration.ofMillis(5), 8);
        CompletableFuture<RpcResponse> first = pending.register(request("corr-close", 500));

        pending.close();

        CompletionException firstException = assertThrows(CompletionException.class, first::join);
        ZeroException firstCause = assertInstanceOf(ZeroException.class, firstException.getCause());
        assertEquals(RpcErrorCode.TRANSPORT_UNAVAILABLE.code(), firstCause.errorCode().code());
        assertEquals(0, pending.size());

        CompletableFuture<RpcResponse> second = pending.register(request("corr-after-close", 500));
        CompletionException secondException = assertThrows(CompletionException.class, second::join);
        ZeroException secondCause = assertInstanceOf(ZeroException.class, secondException.getCause());
        assertEquals(RpcErrorCode.TRANSPORT_UNAVAILABLE.code(), secondCause.errorCode().code());

        pending.close();
    }

    private RpcRequest request(final String correlationId, final long timeoutMillis) {
        return new RpcRequest(
                correlationId,
                "reply",
                "service",
                "method",
                "trace",
                Instant.now().plusMillis(timeoutMillis),
                RpcMode.REQUEST_RESPONSE,
                new byte[0]);
    }

    private RpcResponse response(final String correlationId) {
        return new RpcResponse(correlationId, "trace", SystemErrorCode.OK, new byte[0]);
    }

    private void waitUntil(final BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    private void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
