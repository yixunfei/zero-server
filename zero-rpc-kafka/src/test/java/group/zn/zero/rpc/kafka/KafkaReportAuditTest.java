package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Kafka envelope、时间轮和关闭交错回归。 @author zn */
class KafkaReportAuditTest {
    /** 完整 envelope 后的垃圾必须被拒绝。 */
    @Test void trailingBytesAreRejected() {
        KafkaRpcEnvelopeCodec codec = new KafkaRpcEnvelopeCodec();
        byte[] encoded = codec.encodeResponse(new RpcResponse("c", "t", SystemErrorCode.OK, new byte[0]));
        assertThrows(ZeroException.class, () -> codec.decode(Arrays.copyOf(encoded, encoded.length + 1)));
        byte[] request = codec.encodeRequest(request("c"));
        assertThrows(ZeroException.class, () -> codec.decode(Arrays.copyOf(request, request.length + 1)));
    }
    /** 用桶锁和逻辑 tick 前进确定性重现选桶之后被扫描的交错。 */
    @Test void schedulingRechecksTickAfterAcquiringBucket() throws Exception {
        try (KafkaRpcTimeoutWheel wheel = new KafkaRpcTimeoutWheel(Duration.ofHours(1), 8, (id, token) -> { })) {
            ArrayDeque<?>[] buckets = (ArrayDeque<?>[]) field(wheel, "buckets");
            AtomicLong tick = (AtomicLong) field(wheel, "currentTick");
            Thread scheduler = new Thread(() -> wheel.schedule("c", Instant.now().plusMillis(10), 1));
            synchronized (buckets[2]) {
                scheduler.start();
                long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (scheduler.getState() != Thread.State.BLOCKED && System.nanoTime() < until) Thread.onSpinWait();
                assertEquals(Thread.State.BLOCKED, scheduler.getState());
                tick.set(2); // 相当于目标桶已经完成本轮扫描；调度线程尚未插入。
            }
            scheduler.join(2000);
            assertTrue(!scheduler.isAlive());
            assertEquals(0, buckets[2].size());
            assertEquals(1, buckets[4].size());
        }
    }
    /** 关闭与登记并发完成后不得遗留 future 或容量。 */
    @Test void registrationRacingCloseAlwaysCompletes() throws Exception {
        for (int i = 0; i < 50; i++) {
            try (KafkaRpcPendingRequests pending = new KafkaRpcPendingRequests(1)) {
                CountDownLatch start = new CountDownLatch(1);
                CompletableFuture<CompletableFuture<RpcResponse>> registered = new CompletableFuture<>();
                Thread thread = new Thread(() -> {
                    try { start.await(); registered.complete(pending.register(request("c"))); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); registered.completeExceptionally(ex); }
                });
                thread.start(); start.countDown(); pending.close(); thread.join(2000);
                assertTrue(registered.join().isCompletedExceptionally());
                assertEquals(0, pending.size());
            }
        }
    }
    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private RpcRequest request(String id) {
        return new RpcRequest(id, "reply", "service", "method", "trace", Instant.now().plusSeconds(30),
                RpcMode.REQUEST_RESPONSE, new byte[0]);
    }
}
