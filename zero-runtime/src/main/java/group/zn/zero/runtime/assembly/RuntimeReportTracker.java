package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyReport;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyReport.ComponentStatus;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyReport.FailureSummary;
import group.zn.zero.runtime.diagnostics.RuntimePhaseOutcome;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.health.HealthResult;
import group.zn.zero.runtime.health.RuntimeHealthSnapshot;
import group.zn.zero.runtime.health.RuntimeHealthSnapshot.ComponentHealth;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * runtime 边界内串行更新、对外返回不可变快照的报告状态。
 */
final class RuntimeReportTracker {

    private final PlannedRuntime planned;
    private final Map<ComponentId, MutableStatus> statuses = new LinkedHashMap<>();
    private RuntimeState state = RuntimeState.PLANNED;
    private FailureSummary failure;

    RuntimeReportTracker(final PlannedRuntime planned) {
        this.planned = Objects.requireNonNull(planned, "planned");
        planned.orderedProviders().forEach(provider -> statuses.put(
                provider.descriptor().id(),
                new MutableStatus(provider.descriptor().healthPhases())));
    }

    synchronized void state(final RuntimeState newState) {
        state = Objects.requireNonNull(newState, "newState");
    }

    synchronized void created(
            final ComponentId id,
            final long durationNanos,
            final boolean hasLifecycle) {
        MutableStatus status = status(id);
        status.create = RuntimePhaseOutcome.SUCCEEDED;
        status.createDurationNanos = durationNanos;
        if (!hasLifecycle) {
            status.start = RuntimePhaseOutcome.NOT_APPLICABLE;
            status.stop = RuntimePhaseOutcome.NOT_APPLICABLE;
        }
        if (!status.healthPhases.contains(HealthPhase.STARTUP)) {
            status.startupHealth = RuntimePhaseOutcome.NOT_APPLICABLE;
        }
    }

    synchronized void createFailed(final ComponentId id, final long durationNanos) {
        MutableStatus status = status(id);
        status.create = RuntimePhaseOutcome.FAILED;
        status.createDurationNanos = durationNanos;
    }

    synchronized void started(final ComponentId id, final long durationNanos) {
        MutableStatus status = status(id);
        status.start = RuntimePhaseOutcome.SUCCEEDED;
        status.startDurationNanos = durationNanos;
    }

    synchronized void startFailed(final ComponentId id, final long durationNanos) {
        MutableStatus status = status(id);
        status.start = RuntimePhaseOutcome.FAILED;
        status.startDurationNanos = durationNanos;
    }

    synchronized void health(
            final ComponentId id,
            final RuntimePhaseOutcome outcome,
            final long durationNanos,
            final HealthResult result) {
        MutableStatus status = status(id);
        status.startupHealth = Objects.requireNonNull(outcome, "outcome");
        status.healthDurationNanos = durationNanos;
        if (result != null) {
            status.healthResults.put(HealthPhase.STARTUP, result);
        }
    }

    synchronized void stopped(
            final ComponentId id,
            final RuntimePhaseOutcome outcome,
            final long durationNanos) {
        MutableStatus status = status(id);
        status.stop = Objects.requireNonNull(outcome, "outcome");
        status.stopDurationNanos += durationNanos;
    }

    synchronized void failed(final RuntimeAssemblyException exception) {
        state = RuntimeState.FAILED;
        failure = new FailureSummary(
                exception.code(), exception.phase(), exception.componentId());
    }

    synchronized RuntimeAssemblyReport snapshot(final BuildResourceLedger resources) {
        List<ComponentStatus> componentStatuses = statuses.entrySet().stream()
                .map(entry -> entry.getValue().snapshot(entry.getKey()))
                .toList();
        return new RuntimeAssemblyReport(
                planned.plan(),
                state,
                componentStatuses,
                resources.size(),
                resources.pendingCount(),
                Optional.ofNullable(failure));
    }

    synchronized RuntimeHealthSnapshot healthSnapshot() {
        List<ComponentHealth> health = new ArrayList<>();
        statuses.forEach((id, status) -> status.healthPhases.stream()
                .sorted()
                .forEach(phase -> health.add(new ComponentHealth(
                        id, phase, status.healthResults.getOrDefault(phase, HealthResult.unknown())))));
        return new RuntimeHealthSnapshot(state, Instant.now(), health);
    }

    private MutableStatus status(final ComponentId id) {
        return Objects.requireNonNull(statuses.get(id), "unknown component status");
    }

    /** 单组件可变状态，仅在 tracker monitor 内访问。 */
    private static final class MutableStatus {

        private final Set<HealthPhase> healthPhases;
        private final Map<HealthPhase, HealthResult> healthResults = new EnumMap<>(HealthPhase.class);
        private RuntimePhaseOutcome create = RuntimePhaseOutcome.NOT_RUN;
        private long createDurationNanos;
        private RuntimePhaseOutcome start = RuntimePhaseOutcome.NOT_RUN;
        private long startDurationNanos;
        private RuntimePhaseOutcome startupHealth = RuntimePhaseOutcome.NOT_RUN;
        private long healthDurationNanos;
        private RuntimePhaseOutcome stop = RuntimePhaseOutcome.NOT_RUN;
        private long stopDurationNanos;

        private MutableStatus(final Set<HealthPhase> healthPhases) {
            this.healthPhases = Set.copyOf(healthPhases);
        }

        private ComponentStatus snapshot(final ComponentId id) {
            return new ComponentStatus(
                    id,
                    create,
                    createDurationNanos,
                    start,
                    startDurationNanos,
                    startupHealth,
                    healthDurationNanos,
                    stop,
                    stopDurationNanos,
                    Optional.ofNullable(healthResults.get(HealthPhase.STARTUP)));
        }
    }
}
