package group.zn.zero.runtime.diagnostics;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.health.HealthResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * build/start/stop/close 状态的安全只读快照。
 *
 * @param plan 未创建额外组件或 resource 的计划。
 * @param state runtime 状态。
 * @param components 组件阶段结果。
 * @param buildResourceCount 已登记 build resource 数量。
 * @param pendingResourceCloseCount 尚未成功关闭的资源数量。
 * @param failure 最近主失败；为空表示没有失败。
 * @author zn
 */
public record RuntimeAssemblyReport(
        RuntimeAssemblyPlan plan,
        RuntimeState state,
        List<ComponentStatus> components,
        int buildResourceCount,
        int pendingResourceCloseCount,
        Optional<FailureSummary> failure) {

    public RuntimeAssemblyReport {
        plan = Objects.requireNonNull(plan, "plan");
        state = Objects.requireNonNull(state, "state");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        failure = Objects.requireNonNull(failure, "failure");
        if (buildResourceCount < 0 || pendingResourceCloseCount < 0) {
            throw new IllegalArgumentException("resource counts must not be negative");
        }
    }

    /** 单组件执行状态。 */
    public record ComponentStatus(
            ComponentId componentId,
            RuntimePhaseOutcome create,
            long createDurationNanos,
            RuntimePhaseOutcome start,
            long startDurationNanos,
            RuntimePhaseOutcome startupHealth,
            long healthDurationNanos,
            RuntimePhaseOutcome stop,
            long stopDurationNanos,
            Optional<HealthResult> healthResult) {

        public ComponentStatus {
            componentId = Objects.requireNonNull(componentId, "componentId");
            create = Objects.requireNonNull(create, "create");
            start = Objects.requireNonNull(start, "start");
            startupHealth = Objects.requireNonNull(startupHealth, "startupHealth");
            stop = Objects.requireNonNull(stop, "stop");
            healthResult = Objects.requireNonNull(healthResult, "healthResult");
        }
    }

    /** 不含 raw cause 或配置值的失败摘要。 */
    public record FailureSummary(
            String errorCode,
            RuntimeFailurePhase phase,
            Optional<ComponentId> componentId) {

        public FailureSummary {
            errorCode = Objects.requireNonNull(errorCode, "errorCode");
            phase = Objects.requireNonNull(phase, "phase");
            componentId = Objects.requireNonNull(componentId, "componentId");
        }
    }
}
