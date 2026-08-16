package group.zn.zero.runtime.health;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次显式健康检查请求。
 *
 * @param phase 检查阶段。
 * @param timeout 本次剩余预算。
 * @param requestedAt 请求时间。
 * @author zn
 */
public record HealthRequest(HealthPhase phase, Duration timeout, Instant requestedAt) {

    /**
     * 创建健康请求。
     */
    public HealthRequest {
        phase = Objects.requireNonNull(phase, "phase");
        timeout = Objects.requireNonNull(timeout, "timeout");
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
    }
}
