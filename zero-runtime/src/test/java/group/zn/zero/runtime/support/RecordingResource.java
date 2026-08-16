package group.zn.zero.runtime.support;

import java.util.List;
import java.util.Objects;

/**
 * 可配置关闭失败次数的测试资源。
 */
public final class RecordingResource implements AutoCloseable {

    private final String name;
    private final List<String> events;
    private final String failureMessage;
    private int closeFailuresRemaining;
    private int closeCount;

    public RecordingResource(
            final String name,
            final List<String> events,
            final int closeFailures,
            final String failureMessage) {
        this.name = Objects.requireNonNull(name, "name");
        this.events = Objects.requireNonNull(events, "events");
        this.closeFailuresRemaining = closeFailures;
        this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
    }

    public static RecordingResource healthy(final String name, final List<String> events) {
        return new RecordingResource(name, events, 0, "resource failure");
    }

    @Override
    public synchronized void close() {
        closeCount++;
        events.add("close:" + name);
        if (closeFailuresRemaining > 0) {
            closeFailuresRemaining--;
            throw new IllegalStateException(failureMessage);
        }
    }

    public synchronized int closeCount() {
        return closeCount;
    }
}
