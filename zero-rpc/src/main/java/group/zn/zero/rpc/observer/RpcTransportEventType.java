package group.zn.zero.rpc.observer;

/**
 * RPC 传输观测事件类型。
 *
 * <p>该枚举只描述传输层低基数状态变化，不绑定具体日志、指标或中间件实现。
 *
 * @author zn
 */
public enum RpcTransportEventType {

    /**
     * request/response 请求已完成传输层发送。
     */
    REQUEST_SENT,

    /**
     * oneway 请求已完成传输层发送。
     */
    ONEWAY_SENT,

    /**
     * 请求已被消费端收到。
     */
    REQUEST_RECEIVED,

    /**
     * 请求在执行业务 handler 前被拒绝。
     */
    REQUEST_REJECTED,

    /**
     * 业务 handler 开始执行。
     */
    HANDLER_STARTED,

    /**
     * 业务 handler 成功完成。
     */
    HANDLER_SUCCEEDED,

    /**
     * 业务 handler 执行失败。
     */
    HANDLER_FAILED,

    /**
     * 响应已完成传输层发送。
     */
    RESPONSE_SENT,

    /**
     * 传输层发送失败。
     */
    SEND_FAILED,

    /**
     * pending 请求已注册。
     */
    PENDING_REGISTERED,

    /**
     * pending 请求被拒绝。
     */
    PENDING_REJECTED,

    /**
     * pending 请求收到响应并完成。
     */
    PENDING_COMPLETED,

    /**
     * pending 请求因传输或关闭失败。
     */
    PENDING_FAILED,

    /**
     * pending 请求超时。
     */
    PENDING_TIMED_OUT,

    /**
     * consumer worker 将因异常进入重启流程。
     */
    CONSUMER_RESTARTING
}
