package group.zn.zero.net.netty;

import group.zn.zero.net.ServerOptions;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Collections;
import java.util.List;
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

/** EventLoop 内完整准入、写完成和预算释放；不将编码成本混入出站协调。 @author zn */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class NettyOutboundBenchmark {
    /** 批次帧数。 */
    @Param({"0", "1", "2", "8", "32"}) public int count;
    /** 调用入口。 */
    @Param({"single", "batch"}) public String entry;
    /** 线程独占通道。 */
    private EmbeddedChannel channel;
    /** 真实连接。 */
    private NettyConnection connection;
    /** 独立业务帧。 */
    private final ProtocolFrame frame = new ProtocolFrame(1, 1, 0, null, new byte[64]);
    /** 不可变输入批次。 */
    private List<ProtocolFrame> frames;

    /** 初始化立即完成写的通道；flush 仍经过生产实现。 */
    @Setup public void setup() {
        channel = new EmbeddedChannel(new CompletedWrites());
        connection = new NettyConnection("bench", channel, new ZeroBinaryFrameCodec(),
                ServerOptions.tcp("localhost", 0), new OutboundBudget(64L * 1024 * 1024));
        frames = List.copyOf(Collections.nCopies(count, frame));
    }

    /** @return 实际写完成结果；单入口只适用于 count=1。 */
    @Benchmark public Object send() {
        return (entry.equals("single") && count == 1 ? connection.send(frame) : connection.sendFrames(frames))
                .toCompletableFuture().join();
    }

    /** 验证每次都归还预算并释放通道。 */
    @TearDown public void close() {
        if (connection.pendingOutboundBytes() != 0) throw new IllegalStateException("leaked reservation");
        channel.finishAndReleaseAll();
    }

    /** 同步完成桩；不额外缓存帧。 @author zn */
    private static final class CompletedWrites extends ChannelOutboundHandlerAdapter {
        /** 消费自持有帧并完成真实 Netty promise。 */
        @Override public void write(final ChannelHandlerContext context, final Object message,
                final ChannelPromise promise) { promise.setSuccess(); }
    }
}
