package group.zn.zero.rpc.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** 两个独立服务范围并发替换不能丢失其中一个更新。 @author zn */
class ResolverConcurrencyTest {
    /** 用旧快照读取屏障固定两个更新从同一状态开始。 */
    @Test @SuppressWarnings("unchecked") void scopeUpdatesAreAtomic() throws Exception {
        var resolver = new RoundRobinRpcServiceResolver();
        var field = RoundRobinRpcServiceResolver.class.getDeclaredField("instances");
        field.setAccessible(true);
        var snapshots = (AtomicReference<List<RpcServiceInstance>>) field.get(resolver);
        snapshots.set(new BarrierList());
        CompletableFuture<Void> a = update(resolver, "a");
        CompletableFuture<Void> b = update(resolver, "b");
        CompletableFuture.allOf(a, b).get(3, TimeUnit.SECONDS);
        assertEquals(1, resolver.snapshot(RpcServiceQuery.of("a", 1)).size());
        assertEquals(1, resolver.snapshot(RpcServiceQuery.of("b", 1)).size());
    }
    private static CompletableFuture<Void> update(RoundRobinRpcServiceResolver resolver, String name) {
        var done = new CompletableFuture<Void>();
        Thread.ofVirtual().start(() -> {
            try {
                var instance = new RpcServiceInstance(name, 1, name, "kafka", "topic", "group", "127.0.0.1", 1,
                        RpcDiscoveryMetadata.DEFAULT_GROUP_NAME, RpcDiscoveryMetadata.DEFAULT_CLUSTER_NAME,
                        RpcDiscoveryMetadata.DEFAULT_ZONE, true, true, 1, Map.of());
                resolver.replaceSnapshot(RpcServiceSnapshot.now(RpcServiceQuery.of(name, 1), List.of(instance)));
                done.complete(null);
            } catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        return done;
    }
    /** 仅首个快照使用屏障；CAS 重试读取正常的新列表。 */
    private static final class BarrierList extends AbstractList<RpcServiceInstance> {
        /** 两个更新同时持有旧快照。 */
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        /** 空列表无元素。 */ @Override public RpcServiceInstance get(int index) { throw new IndexOutOfBoundsException(index); }
        /** @return 空快照大小。 */ @Override public int size() { return 0; }
        /** @return 屏障之后的空流。 */ @Override public Stream<RpcServiceInstance> stream() {
            try { barrier.await(2, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            return Stream.empty();
        }
    }
}
