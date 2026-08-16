package group.zn.zero.runtime.health;

import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import java.util.Objects;

/**
 * 不携带异常、地址或原始响应的健康结果。
 *
 * @param status 健康状态。
 * @param code 稳定、低基数诊断码。
 * @author zn
 */
public record HealthResult(HealthStatus status, String code) {

    /**
     * 创建健康结果。
     */
    public HealthResult {
        status = Objects.requireNonNull(status, "status");
        code = RuntimeIdentifiers.requireSafeAlias(code, "healthCode");
    }

    /**
     * 创建健康结果。
     *
     * @param code 稳定诊断码；不可为空。
     * @return 健康结果；不可为空。
     */
    public static HealthResult healthy(final String code) {
        return new HealthResult(HealthStatus.HEALTHY, code);
    }

    /**
     * 创建未知结果。
     *
     * @return 未检查结果；不可为空。
     */
    public static HealthResult unknown() {
        return new HealthResult(HealthStatus.UNKNOWN, "not-checked");
    }
}
