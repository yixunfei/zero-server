package group.zn.zero.runtime.support;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import java.util.List;
import java.util.Objects;

/**
 * 可配置启停失败次数的测试 lifecycle。
 */
public final class RecordingLifecycle implements Lifecycle {

    private final String name;
    private final List<String> events;
    private final String failureMessage;
    private int startFailuresRemaining;
    private int stopFailuresRemaining;
    private LifecycleState state = LifecycleState.NEW;

    public RecordingLifecycle(
            final String name,
            final List<String> events,
            final int startFailures,
            final int stopFailures,
            final String failureMessage) {
        this.name = Objects.requireNonNull(name, "name");
        this.events = Objects.requireNonNull(events, "events");
        this.startFailuresRemaining = startFailures;
        this.stopFailuresRemaining = stopFailures;
        this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
    }

    public static RecordingLifecycle healthy(final String name, final List<String> events) {
        return new RecordingLifecycle(name, events, 0, 0, "lifecycle failure");
    }

    @Override
    public synchronized LifecycleState state() {
        return state;
    }

    @Override
    public synchronized void start() {
        events.add("start:" + name);
        if (startFailuresRemaining > 0) {
            startFailuresRemaining--;
            state = LifecycleState.FAILED;
            throw new IllegalStateException(failureMessage);
        }
        state = LifecycleState.RUNNING;
    }

    @Override
    public synchronized void stop() {
        events.add("stop:" + name);
        if (stopFailuresRemaining > 0) {
            stopFailuresRemaining--;
            state = LifecycleState.FAILED;
            throw new IllegalStateException(failureMessage);
        }
        state = LifecycleState.STOPPED;
    }
}
