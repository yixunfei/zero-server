package group.zn.zero.net;

import group.zn.zero.net.http.HttpRequestHandler;
import group.zn.zero.net.kcp.UnsupportedKcpServer;
import group.zn.zero.net.netty.NettyHttpServer;
import group.zn.zero.net.netty.NettyTcpServer;
import group.zn.zero.net.netty.NettyUdpServer;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 默认服务器工厂。
 *
 * @author zn
 */
public final class ServerFactory {

    private ServerFactory() {
    }

    /**
     * 创建 TCP 服务器。
     *
     * @param options 服务器配置；不可为空。
     * @param handler 协议帧处理器；不可为空。
     * @param executor 业务执行器；不可为空。
     * @return TCP 服务器；不可为空；调用方负责生命周期。
     */
    public static IServer tcp(
            final ServerOptions options,
            final ServerFrameHandler handler,
            final Executor executor) {
        return new NettyTcpServer(options, handler, executor);
    }

    /**
     * 创建 UDP 服务器。
     *
     * @param options 服务器配置；不可为空。
     * @param handler 协议帧处理器；不可为空。
     * @param executor 业务执行器；不可为空。
     * @return UDP 服务器；不可为空；调用方负责生命周期。
     */
    public static IServer udp(
            final ServerOptions options,
            final ServerFrameHandler handler,
            final Executor executor) {
        return new NettyUdpServer(options, new ZeroBinaryFrameCodec(), handler, new ConnectionListener() {
        }, executor);
    }

    /**
     * 创建 HTTP 服务器。
     *
     * @param options 服务器配置；不可为空。
     * @param handler HTTP 请求处理器；不可为空。
     * @param executor 业务执行器；不可为空。
     * @return HTTP 服务器；不可为空；调用方负责生命周期。
     */
    public static IServer http(
            final ServerOptions options,
            final HttpRequestHandler handler,
            final Executor executor) {
        return new NettyHttpServer(options, handler, executor);
    }

    /**
     * 创建暂未实现的 KCP 服务器边界。
     *
     * @param options 服务器配置；不可为空。
     * @return KCP 服务器占位；不可为空；启动时 fail-fast。
     */
    public static IServer kcpUnsupported(final ServerOptions options) {
        return new UnsupportedKcpServer(Objects.requireNonNull(options, "options"));
    }

    /**
     * 创建 TCP 服务器。
     *
     * @param options 服务器配置；不可为空。
     * @param frameCodec 协议帧编解码器；不可为空。
     * @param handler 协议帧处理器；不可为空。
     * @param listener 连接监听器；不可为空。
     * @param executor 业务执行器；不可为空。
     * @return TCP 服务器；不可为空；调用方负责生命周期。
     */
    public static IServer tcp(
            final ServerOptions options,
            final ProtocolFrameCodec frameCodec,
            final ServerFrameHandler handler,
            final ConnectionListener listener,
            final Executor executor) {
        return new NettyTcpServer(options, frameCodec, handler, listener, executor);
    }

    /**
     * 创建显式启用 production lifecycle 的 TCP 服务器。
     *
     * @param options 服务器配置；不可为空；现有构造语义保持不变。
     * @param frameCodec 协议帧编解码器；不可为空。
     * @param handler 协议帧处理器；不可为空；只有 ESTABLISHED 连接会进入。
     * @param listener 连接监听器；不可为空；只有 ESTABLISHED 后收到 onOpen。
     * @param executor 业务执行器；不可为空；调用方负责生命周期。
     * @param lifecycle production lifecycle；不可为空；显式传入才启用。
     * @return production TCP 服务器；不可为空；调用方负责生命周期。
     */
    public static IServer tcp(
            final ServerOptions options,
            final ProtocolFrameCodec frameCodec,
            final ServerFrameHandler handler,
            final ConnectionListener listener,
            final Executor executor,
            final ProductionNetworkLifecycle lifecycle) {
        return new NettyTcpServer(options, frameCodec, handler, listener, executor, lifecycle);
    }

    /**
     * 创建 UDP 服务器。
     *
     * @param options 服务器配置；不可为空。
     * @param frameCodec 协议帧编解码器；不可为空。
     * @param handler 协议帧处理器；不可为空。
     * @param listener 连接监听器；不可为空。
     * @param executor 业务执行器；不可为空。
     * @return UDP 服务器；不可为空；调用方负责生命周期。
     */
    public static IServer udp(
            final ServerOptions options,
            final ProtocolFrameCodec frameCodec,
            final ServerFrameHandler handler,
            final ConnectionListener listener,
            final Executor executor) {
        return new NettyUdpServer(options, frameCodec, handler, listener, executor);
    }
}
