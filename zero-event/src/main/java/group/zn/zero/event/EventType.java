package group.zn.zero.event;

/**
 * 事件类型。
 *
 * @author zn
 */
public enum EventType {

    /**
     * 客户端协议事件。
     */
    CLIENT_PROTOCOL,

    /**
     * 服务内部事件。
     */
    INTERNAL,

    /**
     * 内部跨服与转发事件。
     */
    SERVER_FORWARD,

    /**
     * 业务跨服事件。
     */
    BUSINESS_REMOTE,

    /**
     * 运营后台事件。
     */
    GM,

    /**
     * 定时事件。
     */
    SCHEDULED,

    /**
     * 数据变更事件。
     */
    DATA_CHANGE,

    /**
     * 异常与日志变更事件。
     */
    ERROR_LOG,

    /**
     * 开发指令事件。
     */
    DEV_COMMAND
}

