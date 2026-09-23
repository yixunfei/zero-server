package group.zn.zero.benchmark.performance;

import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.protocol.buffer.ZeroWriter;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** 帧和 UTF-8 分配基准；线程私有，返回值由 JMH 消费。 @author zn */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ProtocolPathBenchmark {
    /** 负载字节数。 */
    @Param({"64", "1024", "32768", "131072"}) public int size;
    /** 不可变输入。 */
    private ProtocolFrame frame;
    /** 编码结果。 */
    private byte[] encoded;
    /** 帧 codec。 */
    private final ZeroBinaryFrameCodec codec = new ZeroBinaryFrameCodec();
    /** 复用 writer。 */
    private ZeroWriter writer;
    /** 混合字符样本。 */
    private String text;
    /** 初始化输入；测量不含构造成本。 */
    @Setup public void setup() {
        frame = new ProtocolFrame(1, 1, 0, null, new byte[size]);
        encoded = codec.encode(frame);
        text = "Ascii中文😀".repeat(Math.max(1, size / 16));
        writer = new ZeroWriter();
    }
    /** @return 独立编码数组；只读输入，线程私有。 */
    @Benchmark public Object encode() { return codec.encode(frame); }
    /** @return 独立帧；只读输入，线程私有。 */
    @Benchmark public Object decode() { return codec.decode(encoded); }
    /** @return 写入后的 writer；修改线程私有缓冲，不物化输出数组。 */
    @Benchmark public Object utf8() {
        writer.reset();
        writer.writeString(text);
        return writer;
    }
}
