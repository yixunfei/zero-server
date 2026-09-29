package group.zn.zero.net.kcp;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionAttributes;
import group.zn.zero.net.DefaultConnectionAttributes;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * KCP 连接句柄；可跨线程发送与关闭，算法状态只由所属 EventLoop 访问。
 * send 成功表示本地有界 KCP 队列已接收，不表示远端 ACK 或业务已处理。
 * @author zn
 */
public final class KcpConnection implements IConnection {
    /** 所属会话，不向业务暴露。 */
    private final KcpSession session;
    /** 独立连接属性。 */
    private final ConnectionAttributes attributes = new DefaultConnectionAttributes();
    /** 可见关闭标志；仅 EventLoop 写入。 */
    private volatile boolean closed;

    KcpConnection(final KcpSession session) { this.session = session; }
    /** @return 稳定非空标识；线程安全，无变更。 */
    @Override public String connectionId() { return "kcp-" + Integer.toUnsignedString(session.ticket.conv()); }
    /** @return KCP；线程安全，无变更。 */
    @Override public ServerType serverType() { return ServerType.KCP; }
    /** @return 最近完成路径验证的 UDP 地址；线程安全，无变更。 */
    @Override public SocketAddress remoteAddress() { return session.remote; }
    /** @return 所属 socket 地址；线程安全，无变更。 */
    @Override public SocketAddress localAddress() { return session.owner.channel().localAddress(); }
    /** @return 线程安全的属性容器；无变更，不存放票据密钥。 */
    @Override public ConnectionAttributes attributes() { return attributes; }
    /** @return 连接是否已关闭；线程安全，无变更。 */
    public boolean isClosed() { return closed; }

    /**
     * 预留内存并提交协议帧；线程安全，预算失败不提交，不自动回退或重放。
     * @param message 不可变 ProtocolFrame。
     * @return 独立完成信号；非法消息、关闭或背压均以带错误码的异常完成。
     */
    @Override public CompletionStage<Void> send(final Object message) {
        if (!(message instanceof ProtocolFrame frame)) return failed(NetErrorCode.INVALID_MESSAGE);
        if (closed) return failed(NetErrorCode.SEND_FAILED);
        // 调用线程只检查自持有 payload；codec 必须在 EventLoop 中串行执行。
        if ((long) frame.payloadLength() + frame.extensionLength() > session.owner.maxFrame()) {
            return failed(NetErrorCode.INVALID_MESSAGE);
        }
        long charge;
        try { charge = session.owner.sendCharge(frame); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        if (!session.budget.reserve(charge)) return failed(NetErrorCode.OUTBOUND_OVERFLOW);
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            session.owner.channel().eventLoop().execute(() -> session.send(frame, charge, result));
        } catch (RuntimeException failure) {
            session.budget.release(charge);
            result.completeExceptionally(ZeroException.of(NetErrorCode.SEND_FAILED, "KCP submit failed", failure));
        }
        return result.minimalCompletionStage();
    }

    /** @return 关闭完成信号；线程安全、幂等，取消不会恢复会话或重放请求。 */
    @Override public CompletionStage<Void> close() { return session.owner.closeSession(session); }
    void markClosed() { closed = true; }
    private static CompletionStage<Void> failed(final NetErrorCode code) {
        return CompletableFuture.failedFuture(ZeroException.of(code, code.message(), null));
    }
}
