package group.zn.zero.net.lifecycle;

import group.zn.zero.net.IConnection;
import group.zn.zero.protocol.ProtocolFrame;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 生产网络握手、鉴权、心跳与重连策略端口。
 *
 * <p>实现必须线程安全。握手校验和心跳识别在连接所属 Netty IO 线程执行，只能做有界、
 * 非阻塞计算；鉴权和重连方法由框架投递到调用方提供的受管远程 IO 执行器。</p>
 *
 * @author zn
 */
@FunctionalInterface
public interface ProductionNetworkPolicy {

    /**
     * 校验首个握手帧。
     *
     * @param connection 传输连接；不可为空。
     * @param handshakeFrame 首个握手帧；不可为空。
     * @return 握手决策；不可为空；线程安全。
     * @throws group.zn.zero.core.error.ZeroException 校验失败时可以抛出绑定 ErrorCode 的统一异常。
     */
    NetworkAdmissionDecision validateHandshake(IConnection connection, ProtocolFrame handshakeFrame);

    /**
     * 异步鉴权连接。
     *
     * <p>方法本身由框架在受管鉴权执行器调用；返回 stage 可以继续异步完成。实现不得创建自有线程池，
     * 不得把 token、密码或密钥写入连接属性、日志或异常消息。</p>
     *
     * @param connection 传输连接；不可为空。
     * @param handshakeFrame 已通过轻量校验的握手帧；不可为空。
     * @return 鉴权决策 stage；不可为空；线程安全。
     */
    default CompletionStage<NetworkAdmissionDecision> authenticate(
            final IConnection connection,
            final ProtocolFrame handshakeFrame) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(handshakeFrame, "handshakeFrame");
        return CompletableFuture.completedFuture(NetworkAdmissionDecision.allow());
    }

    /**
     * 判断已建立连接收到的帧是否为心跳。
     *
     * <p>该方法在 Netty IO 线程执行，只允许有界、非阻塞计算。</p>
     *
     * @param connection 传输连接；不可为空。
     * @param frame 协议帧；不可为空。
     * @return true 表示消费为心跳且不进入业务 dispatcher。
     */
    default boolean isHeartbeat(final IConnection connection, final ProtocolFrame frame) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(frame, "frame");
        return false;
    }

    /**
     * 通过 Actor 消息端口协调同一主体的重连替换。
     *
     * <p>框架会在鉴权成功后、连接进入 ESTABLISHED 前调用。实现若需要修改玩家在线状态，必须只发送
     * player actor 消息；禁止直接持有并修改玩家或旧连接业务 session。</p>
     *
     * @param connection 新连接；不可为空。
     * @param subjectId 鉴权主体标识；不可为空；可能为空字符串。
     * @param reconnectWindow 重连窗口；不可为空。
     * @return 协调完成 stage；不可为空；线程安全。
     */
    default CompletionStage<Void> coordinateReconnect(
            final IConnection connection,
            final String subjectId,
            final Duration reconnectWindow) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(reconnectWindow, "reconnectWindow");
        return CompletableFuture.completedFuture(null);
    }
}
