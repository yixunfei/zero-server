package group.zn.zero.net.lifecycle;

import group.zn.zero.net.IConnection;
import group.zn.zero.protocol.ProtocolFrame;
import java.util.Objects;

/**
 * 生产网络连接与协议帧限流端口。
 *
 * <p>方法在 Netty IO 线程执行，必须是有界、非阻塞、低分配实现。部署阈值由 production starter
 * 或业务显式提供，zero-net 核心不硬编码每 IP 阈值。</p>
 *
 * @author zn
 */
@FunctionalInterface
public interface NetworkRateLimiter {

    /**
     * 判断新连接是否允许进入握手阶段。
     *
     * @param connection 新连接；不可为空。
     * @return true 表示允许。
     */
    boolean allowConnection(IConnection connection);

    /**
     * 判断已建立连接的业务帧是否允许投递。
     *
     * @param connection 已建立连接；不可为空。
     * @param frame 业务协议帧；不可为空。
     * @return true 表示允许投递。
     */
    default boolean allowFrame(final IConnection connection, final ProtocolFrame frame) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(frame, "frame");
        return true;
    }

    /**
     * 返回不限制连接和 frame 的实现。
     *
     * @return permit-all 限流器；不可为空；线程安全。
     */
    static NetworkRateLimiter permitAll() {
        return connection -> {
            Objects.requireNonNull(connection, "connection");
            return true;
        };
    }
}
