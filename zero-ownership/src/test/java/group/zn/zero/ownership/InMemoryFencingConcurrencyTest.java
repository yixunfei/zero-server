package group.zn.zero.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 受控交错验证租约校验与本地状态提交之间不能被 release/claim 插入。 @author zn */
@Timeout(10)
class InMemoryFencingConcurrencyTest {
    /** 通用 ownership 不具备本地原子提交能力时，构造阶段应明确拒绝。 */
    @Test void incompatibleOwnershipStoreIsRejected() {
        var other = (OwnershipStore) Proxy.newProxyInstance(OwnershipStore.class.getClassLoader(),
                new Class<?>[] {OwnershipStore.class}, (proxy, method, args) -> {
                    throw new AssertionError("constructor must not call the unsupported store");
                });
        assertThrows(IllegalArgumentException.class, () -> new InMemoryFencedStateStore<>(other));
    }

    /** 在校验后故意阻塞状态写；释放租约必须等待该提交完成，再产生新的 epoch。 */
    @Test void releaseCannotInterleaveBetweenLeaseCheckAndCommit() throws Exception {
        var ownership = new InMemoryOwnershipStore();
        var resource = new OwnershipResource("audit", "local-fence", 1);
        var owner = new ProcessIncarnation("process", "incarnation");
        var lease = ownership.acquire(resource, owner, Duration.ofMinutes(1));
        var clock = new GatedClock();
        var state = new InMemoryFencedStateStore<String>(ownership, clock);
        var failure = new AtomicReference<Throwable>();
        Thread writer = new Thread(() -> {
            try {
                state.commit(new StateCommitRequest<>(resource, lease, StateVersion.initial(), "before-release", "old"));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "fenced-state-writer");
        Thread releaser = new Thread(() -> ownership.release(lease), "fenced-state-releaser");
        writer.start();
        try {
            assertTrue(clock.entered.await(2, TimeUnit.SECONDS));
            // 仅测试通过反射制造内部内存锁竞争，生产代码不添加测试回调或等待点。
            var field = InMemoryFencedStateStore.class.getDeclaredField("stateLock");
            field.setAccessible(true);
            synchronized (field.get(state)) {
                clock.proceed.countDown();
                awaitBlocked(writer);
                releaser.start();
                awaitBlocked(releaser);
            }
        } finally {
            clock.proceed.countDown();
            writer.join(2000);
            if (releaser.getState() != Thread.State.NEW) releaser.join(2000);
        }
        assertFalse(writer.isAlive());
        assertFalse(releaser.isAlive());
        assertNull(failure.get());
        assertEquals("old", state.read(resource).orElseThrow().value());
        var next = ownership.acquire(resource, owner, Duration.ofMinutes(1));
        assertEquals(lease.epoch() + 1, next.epoch());
        assertThrows(OwnershipException.class, () -> state.commit(
                new StateCommitRequest<>(resource, lease, new StateVersion(1), "stale", "invalid")));
    }

    private static void awaitBlocked(final Thread thread) {
        long start = System.nanoTime();
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2)) {
            Thread.yield();
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }

    /** 只在第一次校验时暂停，使测试能在校验后制造确定的状态锁竞争。 */
    private static final class GatedClock extends Clock {
        /** 校验进入信号。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** 允许校验继续。 */
        private final CountDownLatch proceed = new CountDownLatch(1);

        /** @return UTC 时区。 */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** @return 本测试时钟；仅 UTC 场景使用。 */
        @Override public Clock withZone(final ZoneId zone) { return this; }
        /** @return 屏障放行后的当前时刻。 */
        @Override public Instant instant() {
            entered.countDown();
            try {
                if (!proceed.await(2, TimeUnit.SECONDS)) throw new AssertionError("clock gate did not open");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return Instant.now();
        }
    }
}
