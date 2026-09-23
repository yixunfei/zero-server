package group.zn.zero.discovery;

/**
 * 服务发现事件监听器。
 *
 * @author zn
 */
@FunctionalInterface
public interface ServiceDiscoveryListener {

    /**
     * 处理服务发现事件。
     *
     * @param event 服务发现事件；不可为空。
     */
    void onEvent(ServiceEvent event);
}
