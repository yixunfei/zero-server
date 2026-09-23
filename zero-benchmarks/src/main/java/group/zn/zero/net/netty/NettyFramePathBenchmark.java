package group.zn.zero.net.netty;

import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
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

/** 同包测试 codec 桥实际分配；仅位于 opt-in 基准模块，运行时不反向依赖。 @author zn */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class NettyFramePathBenchmark {
    /** payload 字节数。 */
    @Param({"64", "1024", "32768", "131072"}) public int size;
    /** 不可变业务帧。 */
    private ProtocolFrame frame;
    /** 独立编码输入。 */
    private byte[] encoded;
    /** 线程私有编解码通道。 */
    private EmbeddedChannel encoder;
    /** 线程私有解码通道。 */
    private EmbeddedChannel decoder;
    /** 初始化真实 Netty handler；不创建线程。 */
    @Setup public void setup() {
        var codec = new ZeroBinaryFrameCodec();
        frame = new ProtocolFrame(1, 1, 0, null, new byte[size]);
        encoded = codec.encode(frame);
        encoder = new EmbeddedChannel(new NettyProtocolFrameEncoder(codec));
        decoder = new EmbeddedChannel(new NettyProtocolFrameDecoder(codec));
    }
    /** @return 编码完成的字节数；释放输出 ByteBuf，修改线程私有通道。 */
    @Benchmark public int encode() {
        encoder.writeOutbound(frame);
        ByteBuf bytes = encoder.readOutbound();
        int length = bytes.readableBytes();
        bytes.release();
        return length;
    }
    /** @return 自持有业务帧；ByteBuf 由 decoder 自动释放。 */
    @Benchmark public Object decode() {
        decoder.writeInbound(Unpooled.wrappedBuffer(encoded));
        return decoder.readInbound();
    }
    /** 释放通道剩余资源；线程独占。 */
    @TearDown public void close() { encoder.finishAndReleaseAll(); decoder.finishAndReleaseAll(); }
}
