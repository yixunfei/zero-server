package group.zn.zero.net.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
 * 实验：比较 JDK 临时数组与缓存 CharsetEncoder 的长度计算、nullable 前缀、扩容和批写总成本。
 * 不进入生产编码路径；每个组合先检查黄金字节，非法 surrogate 使用 JDK 的 '?' 替换规则。
 * @author zn
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class Utf8BackendExperiment {
    /** 后端类型。 */
    @Param({"heap", "direct", "netty"}) public String backend;
    /** 字符内容。 */
    @Param({"ascii", "mixed", "invalid"}) public String kind;
    /** 字符数量量级。 */
    @Param({"16", "1024"}) public int size;
    /** 线程私有编码器。 */
    private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    /** 输入字符串。 */
    private String value;
    /** heap/direct 工作内存。 */
    private ByteBuffer storage;
    /** Netty 工作内存。 */
    private ByteBuf netty;
    /** 最后编码长度。 */
    private int length;

    /** 初始化并逐项校验 null/empty、混合字符和不配对 surrogate 的完整线字节。 */
    @Setup public void setup() {
        if (backend.equals("netty")) netty = Unpooled.directBuffer(8);
        else storage = backend.equals("direct") ? ByteBuffer.allocateDirect(8) : ByteBuffer.allocate(8);
        String unit = switch (kind) {
            case "ascii" -> "abcdefgh";
            case "mixed" -> "Ascii中文😀";
            case "invalid" -> "a\ud800中\udc00b";
            default -> throw new IllegalArgumentException(kind);
        };
        for (String sample : new String[] {null, "", "ascii", "中文😀", "\ud800\ud800\udc00\udc00"}) {
            value = sample;
            jdkArray();
            byte[] expected = bytes();
            bulkEncoder();
            if (!Arrays.equals(expected, bytes())) throw new IllegalStateException("UTF-8 mismatch");
        }
        value = unit.repeat(Math.max(1, size / unit.length()));
    }
    /** @return 工作区；包含 JDK UTF-8 长度与内容生成、前缀及容量检查。 */
    @Benchmark public Object jdkArray() {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer output = writable(bytes == null ? 0 : bytes.length);
        writePrefix(output, bytes == null ? 0 : bytes.length + 1);
        if (bytes != null) output.put(bytes);
        length = output.position();
        return output;
    }
    /** @return 工作区；包含独立长度遍历、前缀、容量检查和完整批量编码。 */
    @Benchmark public Object bulkEncoder() {
        int count = utf8Length(value);
        ByteBuffer output = writable(count);
        writePrefix(output, value == null ? 0 : count + 1);
        if (value != null) {
            encoder.reset();
            if (!encoder.encode(CharBuffer.wrap(value), output, true).isUnderflow()
                    || !encoder.flush(output).isUnderflow()) throw new IllegalStateException("encoding failed");
        }
        length = output.position();
        return output;
    }
    private ByteBuffer writable(final int count) {
        int required = Math.addExact(count, 5);
        if (netty != null) {
            if (netty.capacity() < required) netty.capacity(required);
            return netty.nioBuffer(0, netty.capacity());
        }
        if (storage.capacity() < required) {
            storage = storage.isDirect() ? ByteBuffer.allocateDirect(required) : ByteBuffer.allocate(required);
        }
        return storage.clear();
    }
    private byte[] bytes() {
        byte[] result = new byte[length];
        if (netty != null) netty.getBytes(0, result);
        else storage.get(0, result);
        return result;
    }
    private static int utf8Length(final String text) {
        if (text == null) return 0;
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
    private static void writePrefix(final ByteBuffer buffer, final int size) {
        int remaining = size;
        while ((remaining & ~0x7f) != 0) {
            buffer.put((byte) ((remaining & 0x7f) | 0x80));
            remaining >>>= 7;
        }
        buffer.put((byte) remaining);
    }
    /** 释放 Netty 实验内存；其余 ByteBuffer 由 JDK 回收。 */
    @TearDown public void close() { if (netty != null) netty.release(); }
}
