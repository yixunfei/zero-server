package group.zn.zero.net;

/**
 * 连接生命周期监听器。
 *
 * @author zn
 */
public interface ConnectionListener {

    /**
     * 连接打开回调。
     *
     * @param connection 连接；不可为空。
     */
    default void onOpen(final IConnection connection) {
        // 默认无操作。
    }

    /**
     * 连接关闭回调。
     *
     * @param connection 连接；不可为空。
     */
    default void onClose(final IConnection connection) {
        // 默认无操作。
    }

    /**
     * 连接异常回调。
     *
     * @param connection 连接；可能为空。
     * @param cause 异常原因；不可为空。
     */
    default void onException(final IConnection connection, final Throwable cause) {
        // 默认无操作。
    }
}
