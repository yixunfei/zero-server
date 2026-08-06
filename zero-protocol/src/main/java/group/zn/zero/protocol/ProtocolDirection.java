package group.zn.zero.protocol;

/**
 * 协议方向。
 *
 * @author zn
 */
public enum ProtocolDirection {

    /**
     * 客户端请求服务端。
     */
    CLIENT_TO_SERVER,

    /**
     * 服务端响应客户端。
     */
    SERVER_TO_CLIENT,

    /**
     * 服务器间调用。
     */
    SERVER_TO_SERVER
}

