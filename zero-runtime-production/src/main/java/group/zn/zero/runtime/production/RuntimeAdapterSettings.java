package group.zn.zero.runtime.production;

import java.time.Duration;
import java.util.Objects;

/**
 * 单个物理 Adapter 的运行期预算；Redis data/cache 共享同一份设置。
 * @param operationTimeout 驱动 IO 与探测超时，与启动预算无关。
 * @param maxInFlight 最大真实在途操作数。
 * @param probeInterval 恢复探测间隔。
 */
public record RuntimeAdapterSettings(Duration operationTimeout, int maxInFlight, Duration probeInterval) {
    /** 创建不可变、线程安全的设置；非法预算立即拒绝。 */
    public RuntimeAdapterSettings {
        Objects.requireNonNull(operationTimeout, "operationTimeout");
        Objects.requireNonNull(probeInterval, "probeInterval");
        if (operationTimeout.isNegative() || operationTimeout.toMillis() < 1
                || operationTimeout.toMillis() > Integer.MAX_VALUE
                || probeInterval.isNegative() || probeInterval.toMillis() < 1
                || probeInterval.toMillis() > Integer.MAX_VALUE || maxInFlight <= 0) {
            throw new IllegalArgumentException("runtime adapter budgets must be positive and driver-representable");
        }
    }

    /**
     * 按标准配置优先级解析 zero.adapter.{adapter} 的运行预算；无 IO 副作用。
     * @param resolver 配置来源，不可为空。
     * @param adapter 稳定 Adapter 分组名，如 redis、mongo、postgresql、kafka、nacos。
     * @param defaultTimeoutMillis 原生驱动默认超时，正毫秒数。
     * @return 不可变有效设置；线程安全。
     * @throws ProductionAdapterException 配置不是正整数时抛出，只包含键名。
     */
    public static RuntimeAdapterSettings resolve(final ProductionConfigResolver resolver,
            final String adapter, final int defaultTimeoutMillis) {
        Objects.requireNonNull(resolver, "resolver");
        if (!Objects.requireNonNull(adapter, "adapter").matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("adapter must be a stable configuration segment");
        }
        String prefix = "zero.adapter." + adapter + ".";
        return new RuntimeAdapterSettings(
                Duration.ofMillis(resolver.strictPositiveInt(adapter, prefix + "operation-timeout-millis", defaultTimeoutMillis)),
                resolver.strictPositiveInt(adapter, prefix + "max-in-flight", 128),
                Duration.ofMillis(resolver.strictPositiveInt(adapter, prefix + "probe-interval-millis", 2000)));
    }

    /** 创建不重试业务操作的恢复预算；无状态修改，返回不可变对象。 */
    public RuntimeAdapterBudget budget() {
        return new RuntimeAdapterBudget(operationTimeout, operationTimeout, maxInFlight, 0,
                probeInterval, probeInterval, 1.0d);
    }
}
