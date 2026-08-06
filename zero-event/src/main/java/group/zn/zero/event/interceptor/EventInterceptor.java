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
}

