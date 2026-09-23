package group.zn.zero.net.netty;

import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroBuffer;
import group.zn.zero.protocol.buffer.ZeroBuffers;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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

/** 同一线格式的只读输入及嵌套回填；可使用固定基线 jar 复现。 @author zn */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ProtocolBufferBenchmark {
    /** 载荷大小。 */
    @Param({"64", "1024", "32768"}) public int size;
    /** 嵌套层数。 */
    @Param({"1", "8"}) public int depth;
    /** 后端。 */
    @Param({"heap", "direct", "nettyHeap", "nettyDirect", "native"}) public String backend;
    /** 线程私有 writer。 */
    private ZeroWriter writer;
    /** Netty 所有者，仅适配后端使用。 */
    private ByteBuf netty;
    /** 载荷。 */
    private byte[] payload;
    /** 独立稳定的帧。 */
    private ProtocolFrame frame;
    /** 单次编码工作区。 */
    private int[] markers;
    /** 初始化常规缓冲；一次扩容成本由 warmup 消化。 */
    @Setup public void setup() {
        ZeroBuffer buffer = switch (backend) {
            case "heap" -> ZeroBuffers.heap(8);
            case "direct" -> ZeroBuffers.direct(8);
            case "native" -> ZeroBuffers.nativeMemory(8);
            case "nettyHeap", "nettyDirect" -> {
                netty = backend.equals("nettyHeap") ? Unpooled.buffer(8) : Unpooled.directBuffer(8);
                yield new NettyZeroBuffer(netty, 1024 * 1024);
            }
            default -> throw new IllegalArgumentException(backend);
        };
        writer = new ZeroWriter(buffer);
        payload = new byte[size];
        frame = new ProtocolFrame(1, 1, 0, null, payload);
        markers = new int[depth];
    }
    /** @return 自持有帧的只读字段视图；含 reader/视图构造，不复制业务 byte[] 字段。 */
    @Benchmark public Object readView() { return new ZeroReader(frame.payloadView()).readBytesView(size); }
    /** @return 完整嵌套对象编码；包含前缀及重叠移动，保持 VarInt 线格式。 */
    @Benchmark public Object nested() {
        writer.reset();
        for (int i = 0; i < depth; i++) markers[i] = writer.beginObject();
        writer.writeByteArray(payload);
        for (int i = depth - 1; i >= 0; i--) writer.endObject(markers[i]);
        return writer;
    }
    /** 释放基准独占 native/Netty 资源。 */
    @TearDown public void close() { writer.close(); if (netty != null) netty.release(); }
}
