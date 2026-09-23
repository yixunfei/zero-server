package group.zn.zero.net.netty;

import group.zn.zero.net.NetworkTransport;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/** 集中创建网络 IO 资源；AUTO 保持 NIO，显式 EPOLL 不可用时立即失败。 @author zn */
final class NettyTransportFactory {
    private NettyTransportFactory() { }
    static EventLoopGroup eventLoops(final NetworkTransport transport, final int threads) {
        return epoll(transport) ? new EpollEventLoopGroup(threads) : new NioEventLoopGroup(threads);
    }
    static Class<? extends ServerChannel> serverChannel(final NetworkTransport transport) {
        return epoll(transport) ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    }
    private static boolean epoll(final NetworkTransport transport) {
        if (transport != NetworkTransport.EPOLL) return false;
        if (!Epoll.isAvailable()) throw new IllegalStateException("EPOLL requested but unavailable", Epoll.unavailabilityCause());
        return true;
    }
}
