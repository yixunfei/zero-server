package group.zn.zero.event.bus;

import group.zn.zero.event.EventType;
import group.zn.zero.event.ZeroEvent;
import group.zn.zero.event.handler.EventHandler;
import group.zn.zero.event.interceptor.EventInterceptor;
import java.util.concurrent.CompletionStage;

/**
 * 事件总线抽象。
 *
 * @author zn
 */
public interface EventBus {

    /**
     * 注册事件处理器。
     *
     * @param eventType 事件类型；不可为空。
     * @param handler 事件处理器；不可为空。
     * @return 订阅句柄；调用 close 后取消注册；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 注册失败时抛出，必须绑定 ErrorCode。
     */
    default EventSubscription register(final EventType eventType, final EventHandler handler) {
        return register(eventType, handler, 0);
    }

    /**
     * 按优先级注册事件处理器。
     *
     * @param eventType 事件类型；不可为空。
     * @param handler 事件处理器；不可为空。
     * @param priority 处理器优先级，数值越小越早执行。
     * @return 订阅句柄；调用 close 后取消注册；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 注册失败时抛出，必须绑定 ErrorCode。
     */
    EventSubscription register(EventType eventType, EventHandler handler, int priority);

    /**
     * 注册事件拦截器。
     *
     * @param interceptor 事件拦截器；不可为空。
     * @return 订阅句柄；调用 close 后取消注册；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 注册失败时抛出，必须绑定 ErrorCode。
     */
    default EventSubscription addInterceptor(final EventInterceptor interceptor) {
        return addInterceptor(interceptor, 0);
    }

    /**
     * 按优先级注册事件拦截器。
     *
     * @param interceptor 事件拦截器；不可为空。
     * @param priority 拦截器优先级，数值越小越早执行。
     * @return 订阅句柄；调用 close 后取消注册；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 注册失败时抛出，必须绑定 ErrorCode。
     */
    EventSubscription addInterceptor(EventInterceptor interceptor, int priority);

    /**
     * 发布事件。
     *
     * @param event 事件对象；不可为空。
     * @return 发布完成信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 发布失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> publish(ZeroEvent event);
}
