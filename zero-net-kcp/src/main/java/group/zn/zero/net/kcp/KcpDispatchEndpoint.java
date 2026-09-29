package group.zn.zero.net.kcp;

import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.error.NetErrorCode;
import java.util.concurrent.Executor;

/** 双端共用的有界业务调度端口，不暴露算法或线程所有权。 @author zn */
interface KcpDispatchEndpoint {
    /** 已接收业务的响应允许在排空期间发送；线程安全，仍受原预算控制。 */
    default java.util.concurrent.CompletionStage<Void> respond(final group.zn.zero.protocol.ProtocolFrame frame) {
        return connection().sendFrame(frame);
    }
    /** @param bytes 字节。 @return 是否成功预留；线程安全。 */
    boolean reserveInbound(long bytes);
    /** @param bytes 已持有字节，恰好归还一次；线程安全。 */
    void releaseInbound(long bytes);
    /** @return 由组合根持有的非内联执行器。 */
    Executor executor();
    /** @return 当前是否 IO 线程。 */
    boolean inIoThread();
    /** @return 线程安全连接句柄。 */
    IConnection connection();
    /** @return 不可变配置。 */
    KcpOptions options();
    /** @return 当前是否允许执行新业务；线程安全。 */
    boolean authorized();
    /** @return 当前是否关闭；线程安全。 */
    boolean closed();
    /** @return 非阻塞异常观察器与有序生命周期回调。 */
    ConnectionListener listener();
    /** @return 异步业务处理器。 */
    ServerFrameHandler handler();
    /** @param code 失败原因。 @param cause 可空异常；线程安全，延迟关闭避免算法重入。 */
    void closeWithFailure(NetErrorCode code, Throwable cause);
}
