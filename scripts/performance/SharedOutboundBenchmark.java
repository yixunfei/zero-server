package group.zn.zero.net.netty;

import group.zn.zero.net.ServerOptions;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.ThreadParams;

/** 真实 EventLoop 跨线程完成，共享预算对照；Local transport 隔离 OS 网络成本。 @author zn */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SharedOutboundBenchmark {
    /** 消费 EventLoop 数。 */
    @Param({"1", "4"}) public int loops;
    /** 共享同一服务预算或独立预算隔离对照。 */
    @Param({"true", "false"}) public boolean shared;
    /** 连接数，映射生产者。 */
    @Param({"1", "32"}) public int connections;
    /** 测试专用 IO 资源。 */
    private DefaultEventLoopGroup group;
    /** 监听通道。 */
    private Channel server;
    /** 真实连接及出站协调器。 */
    private NettyConnection[] clients;
    /** 不可变业务帧。 */
    private final ProtocolFrame frame = new ProtocolFrame(1, 1, 0, null, new byte[64]);

    /** 创建真实本地传输，各次发送都等待生产实现的完成结果。 */
    @Setup public void setup() throws Exception {
        group = new DefaultEventLoopGroup(loops);
        server = new ServerBootstrap().group(group).channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override protected void initChannel(final LocalChannel channel) {
                        channel.pipeline().addLast(new DiscardFrames());
                    }
                }).bind(LocalAddress.ANY).sync().channel();
        var budget = new OutboundBudget(256L * 1024 * 1024);
        clients = new NettyConnection[connections];
        for (int i = 0; i < clients.length; i++) {
            var channel = new Bootstrap().group(group).channel(LocalChannel.class)
                    .handler(new ChannelInboundHandlerAdapter()).connect(server.localAddress()).sync().channel();
            clients[i] = new NettyConnection("c" + i, channel, new ZeroBinaryFrameCodec(), ServerOptions.tcp("localhost", 0),
                    shared ? budget : new OutboundBudget(256L * 1024 * 1024));
        }
    }

    /** @param producer JMH 生产者。 @return 实际发送完成结果；失败直接终止基准。 */
    @Benchmark public Object send(final ThreadParams producer) {
        return clients[producer.getThreadIndex() % clients.length].send(frame).toCompletableFuture().join();
    }

    /** 所有请求已完成后归还资源，验证没有残余预算。 */
    @TearDown public void close() throws Exception {
        for (NettyConnection client : clients) {
            if (client.pendingOutboundBytes() != 0) throw new IllegalStateException("budget leak");
            client.close().toCompletableFuture().join();
        }
        server.close().sync();
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
    }

    /** 消费自持有 ProtocolFrame，不缓存消息。 @author zn */
    private static final class DiscardFrames extends ChannelInboundHandlerAdapter {
        /** 丢弃测试接收端消息，不持有引用。 */
        @Override public void channelRead(final ChannelHandlerContext context, final Object message) { }
    }
}
