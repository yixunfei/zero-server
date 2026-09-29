package group.zn.zero.net;

import group.zn.zero.net.http.HttpRequestHandler;
import group.zn.zero.net.netty.NettyHttpServer;
import group.zn.zero.net.netty.NettyTcpServer;
import group.zn.zero.net.netty.NettyUdpServer;
import group.zn.zero.net.netty.UdpSessionOptions;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
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
     * 创建带 production lifecycle 的 TCP 服务器。
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
        throw new IllegalStateException(
                "TCP factory requires an explicit ProductionNetworkLifecycle; "
                        + "use tcp(options, codec, handler, listener, executor, lifecycle) "
                        + "or tcpUnmanaged for an intentional low-level endpoint");
    }

    /**
     * Creates an intentionally unmanaged TCP endpoint for protocols that provide
     * their own admission contract. This endpoint has no framework handshake,
     * authentication, replay protection, or connection-level frame policy.
     *
     * @param options server configuration; non-null
     * @param handler protocol frame handler; non-null
     * @param executor business executor; non-null
     * @return unmanaged TCP server
     */
    public static IServer tcpUnmanaged(
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
        throw new IllegalStateException(
                "TCP factory requires an explicit ProductionNetworkLifecycle; "
                        + "use the lifecycle overload or tcpUnmanaged for an intentional low-level endpoint");
    }

    /**
     * Creates an intentionally unmanaged TCP endpoint with a custom codec.
     *
     * @param options server configuration; non-null
     * @param frameCodec frame codec; non-null
     * @param handler protocol frame handler; non-null
     * @param listener connection listener; non-null
     * @param executor business executor; non-null
     * @return unmanaged TCP server
     */
    public static IServer tcpUnmanaged(
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

    /**
     * 创建有界远端地址上下文的 UDP 服务。UDP 不提供鉴权、重传或拥塞控制。
     *
     * @param options UDP 网络配置。
     * @param frameCodec 帧编解码器。
     * @param handler 业务处理器。
     * @param listener 地址上下文监听器。
     * @param executor 业务执行器。
     * @param sessions 地址上下文预算。
     * @return UDP 服务；由调用方管理生命周期。
     */
    public static IServer udp(final ServerOptions options, final ProtocolFrameCodec frameCodec,
            final ServerFrameHandler handler, final ConnectionListener listener,
            final Executor executor, final UdpSessionOptions sessions) {
        return new NettyUdpServer(options, frameCodec, handler, listener, executor, null, sessions);
    }
}
