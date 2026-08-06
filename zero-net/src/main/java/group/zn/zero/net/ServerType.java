package group.zn.zero.net;

/**
 * 网络服务器类型。
 *
 * @author zn
 */
public enum ServerType {

    /**
     * TCP 长连接服务器。
     */
    TCP,

    /**
     * UDP 数据报服务器。
     */
    UDP,

    /**
     * KCP 可靠 UDP 服务器。
     */
    KCP,

    /**
     * WebSocket 服务器。
     */
    WEBSOCKET,

    /**
     * HTTP 请求响应服务器。
     */
    HTTP
}
