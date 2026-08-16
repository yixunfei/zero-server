package group.zn.zero.runtime.health;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.RuntimeState;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * runtime 已知健康结果的不可变快照；读取本身不触发探针。
 *
 * @param runtimeState runtime 状态。
 * @param capturedAt 快照时间。
 * @param components 组件健康结果。
 * @author zn
 */
public record RuntimeHealthSnapshot(
        RuntimeState runtimeState,
        Instant capturedAt,
        List<ComponentHealth> components) {

    /**
     * 创建健康快照。
     */
    public RuntimeHealthSnapshot {
        runtimeState = Objects.requireNonNull(runtimeState, "runtimeState");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
    }

    /**
     * 单个组件的安全健康结果。
     *
     * @param componentId 组件 ID。
     * @param phase 健康阶段。
     * @param result 最近结果。
     * @author zn
     */
    public record ComponentHealth(
            ComponentId componentId,
            HealthPhase phase,
            HealthResult result) {

        /**
         * 创建组件健康结果。
         */
        public ComponentHealth {
            componentId = Objects.requireNonNull(componentId, "componentId");
            phase = Objects.requireNonNull(phase, "phase");
            result = Objects.requireNonNull(result, "result");
        }
    }
}
