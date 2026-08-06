package group.zn.zero.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 内存告警 sink。
 *
 * @author zn
 */
public final class InMemoryAlertSink implements AlertSink {

    /**
     * 告警事件列表。
     */
    private final List<AlertEvent> events = new ArrayList<>();

    /**
     * 写入告警事件。
     *
     * @param event 告警事件；不可为空。
     */
    @Override
    public synchronized void publish(final AlertEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    /**
     * 返回告警事件快照。
     *
     * @return 不可变、有序、可能为空、线程安全的告警事件列表。
     */
    public synchronized List<AlertEvent> events() {
        return List.copyOf(events);
    }
}
