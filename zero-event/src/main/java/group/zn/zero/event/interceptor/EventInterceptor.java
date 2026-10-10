package group.zn.zero.event.interceptor;

import group.zn.zero.event.ZeroEvent;

/**
 * 事件拦截器。
 *
 * @author zn
 */
public interface EventInterceptor {

    /**
     * 在事件发布前执行。
     *
     * @param event 事件对象；不可为空。
     * @return true 表示允许继续发布；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 拦截器执行失败时抛出，必须绑定 ErrorCode。
     */
    boolean beforePublish(ZeroEvent event);

    /**
     * 本次发布结束时逆序通知已进入 beforePublish 的拦截器，包括拒绝或抛错的拦截器。
     * 默认无操作。每次进入配对一次；所有钩子完成后才结束 publish。
     * 完成线程可能不同于 before，不用于恢复线程本地 MDC；禁止等待当前 publish 或阻塞 IO。
     * @param event 本次事件；不可为空。
     * @param failure 当前聚合失败；成功时为空，只读使用，不得修改其异常图。
     * @throws group.zn.zero.core.error.ZeroException 清理失败；总线仍调用其余钩子并汇总失败和死信。
     */
    default void afterPublish(final ZeroEvent event, final Throwable failure) {
        // 可选的事件级完成通知；不改变默认业务重试策略。
    }
}

