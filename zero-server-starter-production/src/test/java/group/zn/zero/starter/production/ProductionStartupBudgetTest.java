package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.lifecycle.AbstractLifecycle;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * {@link ProductionStartupBudget} 累计启动预算测试。
 *
 * @author zn
 */
class ProductionStartupBudgetTest {

    /**
     * 验证单 Adapter 上限、全局累计消耗、不重置预算以及耗尽错误归因。
     */
    @Test
    void remainingShouldUseAdapterCapAndNeverResetTotalElapsedBudget() {
        AtomicLong nanoClock = new AtomicLong(100L);
        ProductionStartupBudget budget = new ProductionStartupBudget(
                Duration.ofSeconds(10),
                Duration.ofSeconds(3),
                nanoClock::get);

        assertEquals(Duration.ofSeconds(10), budget.totalBudget());
        assertEquals(Duration.ofSeconds(3), budget.adapterBudget());
        assertEquals(Duration.ofSeconds(3), budget.remainingFor("kafka-rpc"));

        nanoClock.addAndGet(Duration.ofSeconds(8).toNanos());
        assertEquals(Duration.ofSeconds(2), budget.remainingFor("mongo-data"));
        nanoClock.addAndGet(Duration.ofSeconds(1).toNanos());
        assertEquals(Duration.ofSeconds(1), budget.remainingFor("redis-data"));
        nanoClock.addAndGet(Duration.ofSeconds(1).toNanos());

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> budget.remainingFor("postgresql-data"));
        assertEquals("postgresql-data", failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.STARTUP_BUDGET, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED, failure.errorCode());
        assertEquals(ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED.message(), failure.message());
    }

    /**
     * 验证总预算等于单 Adapter 上限时，首次阶段获得完整预算，不被时钟采样开销误判耗尽。
     */
    @Test
    void firstAdapterShouldReceiveExactEqualBudget() {
        AtomicLong nanoClock = new AtomicLong(100L);
        ProductionStartupBudget budget = new ProductionStartupBudget(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                nanoClock::getAndIncrement);
        CountingLifecycle delegate = new CountingLifecycle();
        ProductionAdapterDiagnostic diagnostic = diagnostic("nacos-discovery");

        new ProductionAdapterLifecycle(
                diagnostic,
                budget,
                delegate,
                Duration.ofSeconds(1)).start();

        assertEquals(1, delegate.startCalls);
        assertSame(ZeroProductionAdapterState.STARTED, diagnostic.snapshot().state());
    }

    /**
     * 验证前序阶段消耗预算后，后置 Adapter 的固定原生 timeout 超过 remaining 时不会进入驱动。
     */
    @Test
    void laterAdapterShouldRejectNativeTimeoutBeyondRemaining() {
        AtomicLong nanoClock = new AtomicLong(100L);
        ProductionStartupBudget budget = new ProductionStartupBudget(
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                nanoClock::get);
        budget.remainingFor("first-adapter");
        nanoClock.addAndGet(Duration.ofSeconds(8).toNanos());
        CountingLifecycle delegate = new CountingLifecycle();

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> new ProductionAdapterLifecycle(
                        diagnostic("nacos-discovery"),
                        budget,
                        delegate,
                        Duration.ofSeconds(5)).start());

        assertEquals(0, delegate.startCalls);
        assertEquals(ProductionAdapterFailurePhase.STARTUP_BUDGET, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED, failure.errorCode());
    }

    /**
     * 验证空、零、负数、单 Adapter 超过总预算和空时钟均在构造期失败。
     */
    @Test
    void constructorShouldRejectInvalidBudgetsAndClock() {
        assertThrows(
                NullPointerException.class,
                () -> new ProductionStartupBudget(null, Duration.ofSeconds(1)));
        assertThrows(
                NullPointerException.class,
                () -> new ProductionStartupBudget(Duration.ofSeconds(1), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProductionStartupBudget(Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProductionStartupBudget(Duration.ofSeconds(1), Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProductionStartupBudget(Duration.ofSeconds(-1), Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProductionStartupBudget(Duration.ofSeconds(1), Duration.ofSeconds(-1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProductionStartupBudget(Duration.ofSeconds(1), Duration.ofSeconds(2)));
        assertThrows(
                NullPointerException.class,
                () -> new ProductionStartupBudget(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        null));
    }

    private ProductionAdapterDiagnostic diagnostic(final String adapterName) {
        return new ProductionAdapterDiagnostic(
                adapterName,
                ZeroProductionAdapterState.CREATED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    /** 记录真实 Adapter 是否进入启动逻辑的测试生命周期。 */
    private static final class CountingLifecycle extends AbstractLifecycle {

        /** 启动调用次数；仅由当前测试线程访问。 */
        private int startCalls;

        /** 记录一次启动，不修改外部业务数据。 */
        @Override
        protected void doStart() {
            startCalls++;
        }
    }
}
