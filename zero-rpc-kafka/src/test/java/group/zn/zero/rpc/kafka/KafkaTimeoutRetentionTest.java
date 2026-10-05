package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 长超时请求的取消与登记交错不得绕过 pending 内存上界。 @author zn */
class KafkaTimeoutRetentionTest {
    /** 独立受管计时器，测试结束统一关闭。 */
    @org.junit.jupiter.api.extension.RegisterExtension
    private final KafkaTestTimer timer = new KafkaTestTimer();

    /** 即使截止在一小时后，容量为一的快速完成请求也不应积累超时记录。 */
    @Test void completedCancelledAndFailedRequestsReleaseTimeoutRecords() throws Exception {
        try (var pending = new KafkaRpcPendingRequests(1, Duration.ofHours(1), 8, timer.executor())) {
            Map<?, ?>[] buckets = buckets(pending);
            for (int index = 0; index < 300; index++) {
                String id = "long-" + index;
                var future = pending.register(request(id));
                assertFalse(future.isDone());
                switch (index % 3) {
                    case 0 -> pending.complete(response(id));
                    case 1 -> future.cancel(false);
                    default -> pending.fail(id, RpcErrorCode.TRANSPORT_UNAVAILABLE, "send failed", null);
                }
                assertTrue(future.isDone());
                assertEquals(0, pending.size());
                assertEquals(0, retained(buckets), "completed requests must release their timeout records");
            }
        }
    }

    /** 回包早于超时记录登记时，登记线程必须回收其迟到的记录。 */
    @Test void responseBeforeTimeoutRegistrationDoesNotLeakRecord() throws Exception {
        try (var pending = new KafkaRpcPendingRequests(1, Duration.ofHours(1), 8, timer.executor())) {
            Map<?, ?>[] buckets = buckets(pending);
            var registration = new CompletableFuture<CompletableFuture<RpcResponse>>();
            Thread thread = new Thread(() -> registration.complete(pending.register(request("early"))));
            synchronized (buckets[2]) {
                thread.start();
                long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < until) Thread.onSpinWait();
                assertEquals(Thread.State.BLOCKED, thread.getState());
                assertTrue(pending.complete(response("early")));
            }
            thread.join(2000);
            assertFalse(thread.isAlive());
            assertEquals("early", registration.get(2, TimeUnit.SECONDS).get(2, TimeUnit.SECONDS).correlationId());
            assertEquals(0, retained(buckets));
        }
    }

    private static Map<?, ?>[] buckets(final KafkaRpcPendingRequests pending) throws Exception {
        Object wheel = field(pending, "timeoutWheel");
        return (Map<?, ?>[]) field(wheel, "buckets");
    }

    private static Object field(final Object target, final String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int retained(final Map<?, ?>[] buckets) {
        int retained = 0;
        for (var bucket : buckets) {
            synchronized (bucket) { retained += bucket.size(); }
        }
        return retained;
    }

    private static RpcRequest request(final String id) {
        return new RpcRequest(id, "reply", "service", "method", "trace", Instant.now().plusSeconds(3500),
                RpcMode.REQUEST_RESPONSE, new byte[0]);
    }

    private static RpcResponse response(final String id) {
        return new RpcResponse(id, "trace", SystemErrorCode.OK, new byte[0]);
    }
}
