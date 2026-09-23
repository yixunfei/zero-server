package group.zn.zero.benchmark.experiment;

import group.zn.zero.benchmark.generated.dto.BenchNodeDTO;
import group.zn.zero.benchmark.generated.dto.codec.BenchNodeDTOCodec;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroReader;
import group.zn.zero.protocol.buffer.ZeroWriter;
import java.util.Arrays;
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

/**
 * 显式实验：真实生成 DTO/codec 与有界长度规划原型比较；不修改生成物或生产 SPI。
 * 规划按当前调用重新遍历字符串/集合/子对象，计时包含规划和写入两个阶段。
 * @author zn
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class GeneratedDtoSizingBenchmark {
    /** 后端。 */
    @Param({"heap", "direct"}) public String backend;
    /** 对象深度。 */
    @Param({"1", "8"}) public int depth;
    /** 每层字符串的字符数量量级。 */
    @Param({"16", "1024"}) public int size;
    /** 可变的真实生成 DTO。 */
    private BenchNodeDTO message;
    /** 当前调用的有界工作区，绝不跨调用缓存 DTO 尺寸。 */
    private final int[] lengths = new int[64];
    /** 线程独占 writer。 */
    private ZeroWriter writer;
    /** 自持有解码输入。 */
    private ProtocolFrame frame;

    /** 创建真实生成 DTO；用黄金字节验证长度原型不改变对象头、presence 和字段顺序。 */
    @Setup public void setup() {
        writer = backend.equals("direct") ? ZeroWriter.direct(8) : new ZeroWriter(8);
        for (int i = 0; i < depth; i++) {
            BenchNodeDTO node = new BenchNodeDTO();
            node.id = -123456789;
            node.text = "Ascii中文😀".repeat(Math.max(1, size / 9));
            for (int j = 0; j < 16; j++) node.values.add(j % 2 == 0 ? -j * 700 : j * 1000);
            node.next = message;
            message = node;
        }
        generated();
        byte[] golden = writer.toByteArray();
        planned();
        if (!Arrays.equals(golden, writer.toByteArray())) throw new IllegalStateException("planned bytes differ");
        frame = new ProtocolFrame(1, 1, 0, null, golden);
    }

    /** @return 真实生成 codec 编码完成的 writer，含已有 VarInt 回填。 */
    @Benchmark public Object generated() {
        writer.reset();
        BenchNodeDTOCodec.INSTANCE.write(writer, message);
        return writer;
    }
    /** @return 原型先规划一次全部对象长度，再顺序写入；不计为生产实现。 */
    @Benchmark public Object planned() {
        plan(message, 0);
        writer.reset();
        writePlanned(message, 0);
        return writer;
    }
    /** @return 原数组分发的完整 DTO 解码，含 Frame getter 防御复制。 */
    @Benchmark public Object decodeArray() { return BenchNodeDTOCodec.INSTANCE.read(new ZeroReader(frame.payload())); }
    /** @return 只读分发的完整 DTO 解码，含字符串、集合和嵌套对象。 */
    @Benchmark public Object decodeView() { return BenchNodeDTOCodec.INSTANCE.read(new ZeroReader(frame.payloadView())); }

    /** @return 框架自持有 Frame 的直接只读 reader，避免 ByteBuffer 及字段中间副本。 */
    @Benchmark public Object decodeFrame() { return BenchNodeDTOCodec.INSTANCE.read(frame.payloadReader()); }

    private int plan(final BenchNodeDTO node, final int index) {
        if (index >= lengths.length) throw new IllegalArgumentException("experiment depth exceeds bounded plan");
        int textBytes = utf8Length(node.text);
        int body = 2 + unsignedSize((node.id << 1) ^ (node.id >> 63))
                + unsignedSize(textBytes) + textBytes + unsignedSize(node.values.size());
        for (int value : node.values) body += unsignedSize(Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
        if (node.next != null) body += plan(node.next, index + 1);
        lengths[index] = body;
        return unsignedSize(body) + body;
    }
    private void writePlanned(final BenchNodeDTO node, final int index) {
        writer.writeUnsignedInt(lengths[index]);
        writer.writePresenceBits(1, ignored -> node.next != null);
        writer.writeLong(node.id);
        writer.writeString(node.text);
        writer.writeCollection(node.values, ZeroWriter::writeInt);
        if (node.next != null) writePlanned(node.next, index + 1);
    }
    private static int unsignedSize(final long number) {
        long remaining = number;
        int bytes = 1;
        while ((remaining & ~0x7fL) != 0) { remaining >>>= 7; bytes++; }
        return bytes;
    }
    private static int utf8Length(final String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current < 0x80) count++;
            else if (current < 0x800) count += 2;
            else if (!Character.isSurrogate(current)) count += 3;
            else if (Character.isHighSurrogate(current) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) { count += 4; i++; }
            else count++;
        }
        return count;
    }
    /** 归还独占内存。 */
    @TearDown public void close() { writer.close(); }
}
