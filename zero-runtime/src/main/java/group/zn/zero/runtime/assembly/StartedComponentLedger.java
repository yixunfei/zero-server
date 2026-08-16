package group.zn.zero.runtime.assembly;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.diagnostics.RuntimePhaseOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 只记录成功完成 start 的 lifecycle，并按实际顺序逆序停止。
 */
final class StartedComponentLedger {

    private final List<Entry> entries = new ArrayList<>();

    void record(final ComponentId componentId, final Lifecycle lifecycle) {
        entries.add(new Entry(
                Objects.requireNonNull(componentId, "componentId"),
                Objects.requireNonNull(lifecycle, "lifecycle")));
    }

    RuntimeAssemblyException stopAll(
            final RuntimeAssemblyException original,
            final RuntimeReportTracker tracker) {
        RuntimeAssemblyException primary = original;
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            if (entry.stopped) {
                continue;
            }
            long startedAt = System.nanoTime();
            try {
                entry.lifecycle.stop();
                entry.stopped = true;
                tracker.stopped(entry.componentId, RuntimePhaseOutcome.SUCCEEDED, elapsed(startedAt));
            } catch (Throwable failure) {
                tracker.stopped(entry.componentId, RuntimePhaseOutcome.FAILED, elapsed(startedAt));
                RuntimeAssemblyException safeFailure = RuntimeAssemblyException.failure(
                        RuntimeErrorCode.RUNTIME_COMPONENT_STOP_FAILED,
                        RuntimeFailurePhase.STOP,
                        entry.componentId,
                        "component=" + entry.componentId);
                if (primary == null) {
                    primary = safeFailure;
                } else {
                    primary.addSuppressed(safeFailure);
                }
            }
        }
        return primary;
    }

    private long elapsed(final long startedAt) {
        return Math.max(0L, System.nanoTime() - startedAt);
    }

    private static final class Entry {

        private final ComponentId componentId;
        private final Lifecycle lifecycle;
        private boolean stopped;

        private Entry(final ComponentId componentId, final Lifecycle lifecycle) {
            this.componentId = componentId;
            this.lifecycle = lifecycle;
        }
    }
}
