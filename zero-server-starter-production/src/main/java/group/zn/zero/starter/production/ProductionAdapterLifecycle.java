package group.zn.zero.starter.production;

import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.core.lifecycle.Lifecycle;
import java.time.Duration;
import java.util.Objects;

/**
 * 为 Production Adapter 提供统一预算、状态和安全异常边界的生命周期包装器。
 *
 * <p>包装器在调用真实 Adapter 前检查共享累计启动预算；启动成功后把诊断状态推进到
 * {@link ZeroProductionAdapterState#STARTED}。真实 Adapter 的任意原始失败均会被转换为不保留第三方
 * Throwable 图的 {@link ProductionAdapterException}。
 *
 * @author zn
 */
final class ProductionAdapterLifecycle extends AbstractLifecycle {

    /** Adapter 诊断状态。 */
    private final ProductionAdapterDiagnostic diagnostic;

    /** 共享累计启动预算。 */
    private final ProductionStartupBudget startupBudget;

    /** 被包装的真实生命周期；为空表示仅用于无生命周期能力的启动状态标记。 */
    private final Lifecycle delegate;

    /** 进入真实 start 前必须仍可使用的原生 timeout；零表示只要求累计预算尚未耗尽。 */
    private final Duration requiredStartupTimeout;

    /**
     * 创建真实 Adapter 生命周期包装器。
     *
     * @param diagnostic Adapter 诊断状态；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @param delegate 真实生命周期；不可为空。
     * @throws NullPointerException 任一参数为空时抛出。
     */
    ProductionAdapterLifecycle(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionStartupBudget startupBudget,
            final Lifecycle delegate) {
        this(diagnostic, startupBudget, delegate, Duration.ZERO);
    }

    /**
     * 创建带原生启动 timeout 下界的真实 Adapter 生命周期包装器。
     *
     * @param diagnostic Adapter 诊断状态；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @param delegate 真实生命周期；不可为空。
     * @param requiredStartupTimeout 真实 start 使用的原生 timeout；不可为空、不可为负。
     */
    ProductionAdapterLifecycle(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionStartupBudget startupBudget,
            final Lifecycle delegate,
            final Duration requiredStartupTimeout) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.requiredStartupTimeout = requireNonNegative(requiredStartupTimeout);
    }

    /**
     * 创建不调用外部组件、只推进启动状态的标记生命周期。
     *
     * @param diagnostic Adapter 诊断状态；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @return 标记生命周期；不可为空，尚未启动，非线程安全创建。
     */
    static ProductionAdapterLifecycle marker(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionStartupBudget startupBudget) {
        return new ProductionAdapterLifecycle(diagnostic, startupBudget, null, true);
    }

    private ProductionAdapterLifecycle(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionStartupBudget startupBudget,
            final Lifecycle delegate,
            final boolean marker) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        this.delegate = delegate;
        this.requiredStartupTimeout = Duration.ZERO;
        if (!marker && delegate == null) {
            throw new NullPointerException("delegate");
        }
    }

    /**
     * 检查累计预算、启动真实 Adapter 并推进诊断状态。
     *
     * @throws ProductionAdapterException 启动预算耗尽或真实 Adapter 启动失败时抛出安全异常。
     */
    @Override
    protected void doStart() {
        try {
            Duration remaining = startupBudget.remainingFor(diagnostic.adapterName());
            if (remaining.compareTo(requiredStartupTimeout) < 0) {
                throw ProductionAdapterFailures.failure(
                        diagnostic.adapterName(),
                        ProductionAdapterFailurePhase.STARTUP_BUDGET,
                        ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED,
                        ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED.message());
            }
            if (delegate != null) {
                delegate.start();
            }
            diagnostic.mark(ZeroProductionAdapterState.STARTED);
        } catch (RuntimeException | Error failure) {
            ProductionAdapterException safeFailure = ProductionAdapterFailures.sanitize(
                    diagnostic.adapterName(),
                    ProductionAdapterFailurePhase.STARTUP,
                    ProductionAdapterErrorCode.STARTUP_FAILED,
                    ProductionAdapterErrorCode.STARTUP_FAILED.message(),
                    failure);
            diagnostic.fail(safeFailure);
            throw safeFailure;
        }
    }

    /**
     * 停止真实 Adapter；标记生命周期不执行外部动作。
     *
     * @throws ProductionAdapterException 真实 Adapter 关闭失败时抛出安全异常。
     */
    @Override
    protected void doStop() {
        if (delegate == null) {
            return;
        }
        try {
            delegate.stop();
        } catch (RuntimeException | Error failure) {
            ProductionAdapterException safeFailure = ProductionAdapterFailures.sanitize(
                    diagnostic.adapterName(),
                    ProductionAdapterFailurePhase.CLOSE,
                    ProductionAdapterErrorCode.CLOSE_FAILED,
                    ProductionAdapterErrorCode.CLOSE_FAILED.message(),
                    failure);
            if (diagnostic.snapshot().state() != ZeroProductionAdapterState.FAILED) {
                diagnostic.fail(safeFailure);
            }
            throw safeFailure;
        }
    }

    private static Duration requireNonNegative(final Duration timeout) {
        Duration current = Objects.requireNonNull(timeout, "requiredStartupTimeout");
        if (current.isNegative()) {
            throw new IllegalArgumentException("requiredStartupTimeout must not be negative");
        }
        return current;
    }

}
