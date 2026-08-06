package group.zn.zero.monitor;

/**
 * 告警事件落地接口。
 *
 * @author zn
 */
public interface AlertSink {

    /**
     * 写入告警事件。
     *
     * @param event 告警事件；不可为空。
     * @throws NullPointerException 当告警事件为空时抛出。
     */
    void publish(AlertEvent event);
}
