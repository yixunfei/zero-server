package group.zn.zero.event.handler;

import group.zn.zero.event.ZeroEvent;

/**
 * 同步事件处理器。
 *
 * @author zn
 */
@FunctionalInterface
public interface SyncEventHandler {

    /**
     * 同步处理事件。
     *
     * @param event 事件对象；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 处理失败时抛出，必须绑定 ErrorCode。
     */
    void handle(ZeroEvent event);
}
