package group.zn.zero.runtime.production;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.Objects;

/**
 * Production Adapter 串行启动累计预算。
 *
 * <p>预算只在进入驱动调用前计算剩余时间并限制单 Adapter timeout，不声称能够中止忽略驱动原生 timeout 或
 * interrupt 的同步调用。
 *
 * @author zn
 */
public final class ProductionStartupBudget {

    /** 尚未开始标记。 */
    private static final long NOT_STARTED = Long.MIN_VALUE;

    /** 全局累计预算。 */
    private final Duration totalBudget;

    /** 单 Adapter 最大预算。 */
    private final Duration adapterBudget;

    /** 单调纳秒时钟。 */
    private final LongSupplier nanoClock;

    /** 启动基准纳秒。 */
    private final AtomicLong startedAtNanos = new AtomicLong(NOT_STARTED);

    /**
     * 创建累计预算。
     *
     * @param totalBudget 全局累计预算；必须为正。
     * @param adapterBudget 单 Adapter 最大预算；必须为正且不大于全局预算。
     */
    public ProductionStartupBudget(final Duration totalBudget, final Duration adapterBudget) {
        this(totalBudget, adapterBudget, System::nanoTime);
    }

    /** 包级测试构造器。 */
    public ProductionStartupBudget(
            final Duration totalBudget,
            final Duration adapterBudget,
            final LongSupplier nanoClock) {
        this.totalBudget = requirePositive(totalBudget, "totalBudget");
        this.adapterBudget = requirePositive(adapterBudget, "adapterBudget");
        if (adapterBudget.compareTo(totalBudget) > 0) {
            throw new IllegalArgumentException("adapterBudget must not exceed totalBudget");
        }
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /**
     * 返回当前 Adapter 可使用的剩余预算；第一次调用同时建立 Adapter 阶段预算起点。
     *
     * @param adapterName Adapter 稳定名称；不可为空。
     * @return 正数剩余时长；不大于单 Adapter 上限，线程安全。
     * @throws ProductionAdapterException 累计预算耗尽时抛出安全异常。
     */
    public Duration remainingFor(final String adapterName) {
        String currentAdapter = Objects.requireNonNull(adapterName, "adapterName");
        long now = nanoClock.getAsLong();
        long started = startedAtNanos.get();
        if (started == NOT_STARTED && startedAtNanos.compareAndSet(NOT_STARTED, now)) {
            started = now;
        } else if (started == NOT_STARTED) {
            started = startedAtNanos.get();
        }
        long elapsed = Math.max(0L, now - started);
        long remaining = totalBudget.toNanos() - elapsed;
        if (remaining <= 0L) {
            throw ProductionAdapterFailures.failure(
                    currentAdapter,
                    ProductionAdapterFailurePhase.STARTUP_BUDGET,
                    ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED,
                    ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED.message());
        }
        return Duration.ofNanos(Math.min(remaining, adapterBudget.toNanos()));
    }

    /**
     * 返回全局累计预算。
     *
     * @return 正数时长；不可为空，线程安全。
     */
    public Duration totalBudget() {
        return totalBudget;
    }

    /**
     * 返回单 Adapter 最大预算。
     *
     * @return 正数时长；不可为空，线程安全。
     */
    public Duration adapterBudget() {
        return adapterBudget;
    }

    private static Duration requirePositive(final Duration duration, final String name) {
        Duration current = Objects.requireNonNull(duration, name);
        if (current.isZero() || current.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return current;
    }
}
