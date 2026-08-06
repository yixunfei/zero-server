package group.zn.zero.starter.production;

import group.zn.zero.core.lifecycle.AbstractLifecycle;
import java.time.Duration;
import java.util.Objects;

/**
 * Adapter 健康检查生命周期组件。
 *
 * <p>该组件在 start 阶段执行一次健康探测；失败时会更新诊断状态并向上抛出异常，由
 * `ZeroRuntimeComponents` 负责回滚已启动组件。
 *
 * @author zn
 */
final class AdapterHealthCheckLifecycle extends AbstractLifecycle {

    /**
     * Adapter 诊断状态。
     */
    private final ProductionAdapterDiagnostic diagnostic;

    /**
     * 健康检查动作。
     */
    private final ProductionHealthProbe check;

    /**
     * 共享累计启动预算。
     */
    private final ProductionStartupBudget startupBudget;

    /**
     * 创建 Adapter 健康检查生命周期组件。
     *
     * @param diagnostic Adapter 诊断状态；不可为空。
     * @param check 健康检查动作；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     */
    AdapterHealthCheckLifecycle(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionHealthProbe check,
            final ProductionStartupBudget startupBudget) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.check = Objects.requireNonNull(check, "check");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
    }

    /**
     * 返回当前生命周期实际执行的启动健康探针。
     *
     * <p>该包级冷路径 seam 只用于装配契约测试读取探针类型和输入，不执行检查、不修改诊断状态。
     *
     * @return 健康探针；不可为空，线程安全性由具体探针声明。
     */
    ProductionHealthProbe healthProbe() {
        return check;
    }

    /**
     * 返回当前强制启动健康检查归属的稳定 Adapter 名称。
     *
     * <p>该包级冷路径 seam 只用于装配契约测试，不执行探针、不读取配置原值。
     *
     * @return Adapter 稳定名称；不可为空，线程安全。
     */
    String adapterName() {
        return diagnostic.adapterName();
    }

    /**
     * 执行健康检查。
     */
    @Override
    protected void doStart() {
        try {
            Duration timeout = startupBudget.remainingFor(diagnostic.adapterName());
            check.check(timeout);
            diagnostic.mark(ZeroProductionAdapterState.HEALTHY);
        } catch (RuntimeException | Error ex) {
            ProductionAdapterException failure = ProductionAdapterFailures.sanitize(
                    diagnostic.adapterName(),
                    ProductionAdapterFailurePhase.STARTUP_HEALTH,
                    ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED,
                    ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED.message(),
                    ex);
            diagnostic.fail(failure);
            throw failure;
        }
    }
}
