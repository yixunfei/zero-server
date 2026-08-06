package group.zn.zero.core.error;

/**
 * 错误码分类。
 *
 * @author zn
 */
public enum ErrorCategory {

    /**
     * 客户端业务错误。
     */
    CLIENT_REQUEST,

    /**
     * 服务器间调用错误。
     */
    SERVER_CALL,

    /**
     * 数据访问错误。
     */
    DATA_ACCESS,

    /**
     * 缓存错误。
     */
    CACHE,

    /**
     * 权限错误。
     */
    PERMISSION,

    /**
     * 协议错误。
     */
    PROTOCOL,

    /**
     * 热更错误。
     */
    HOT_UPDATE,

    /**
     * 系统错误。
     */
    SYSTEM
}

