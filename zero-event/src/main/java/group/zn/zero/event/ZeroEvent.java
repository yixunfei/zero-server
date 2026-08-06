package group.zn.zero.event;

/**
 * zeroServer 事件基础接口。
 *
 * @author zn
 */
public interface ZeroEvent {

    /**
     * 返回事件唯一标识。
     *
     * @return 事件标识；不可为空；线程安全性由实现声明。
     */
    String eventId();

    /**
     * 返回事件类型。
     *
     * @return 事件类型；不可为空；线程安全性由实现声明。
     */
    EventType eventType();

    /**
     * 返回链路追踪标识。
     *
     * @return traceId；不可为空；线程安全性由实现声明。
     */
    String traceId();
}

