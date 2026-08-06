package group.zn.zero.protocol.buffer;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * 协议缓冲区借用切片。
 *
 * <p>切片不会复制底层内容，适合读取大 bytes、payload 或压缩块时减少分配。
 * 如果底层缓冲区随后被复用、扩容、修改或释放，切片观察到的内容也会受到影响。
 * 需要长期保存时应调用 {@link #toByteArray()} 创建副本。所有方法线程不安全。
 *
 * @author zn
 */
public final class ZeroBufferSlice {

    /**
     * 底层缓冲区。
     */
    private final ZeroBuffer buffer;

    /**
     * 切片起始位置。
     */
    private final int offset;

    /**
     * 切片长度。
     */
    private final int length;

    /**
     * 创建缓冲区切片。
     *
     * @param buffer 底层缓冲区；不可为空。
     * @param offset 起始位置。
     * @param length 长度。
     * @throws NullPointerException 当缓冲区为空时抛出。
     * @throws IndexOutOfBoundsException 当范围越界时抛出。
     */
    public ZeroBufferSlice(final ZeroBuffer buffer, final int offset, final int length) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        Objects.checkFromIndexSize(offset, length, buffer.capacity());
        this.offset = offset;
        this.length = length;
    }

    /**
     * 返回切片长度。
     *
     * @return 字节长度；非负；线程安全。
     */
    public int length() {
        return length;
    }

    /**
     * 返回是否为空切片。
     *
     * @return true 表示长度为 0；线程安全。
     */
    public boolean isEmpty() {
        return length == 0;
    }

    /**
     * 判断底层是否暴露堆内数组。
     *
     * @return true 表示可以调用 {@link #array()} 与 {@link #arrayOffset()}；线程安全。
     */
    public boolean hasArray() {
        return buffer.hasArray();
    }

    /**
     * 返回底层数组。
     *
     * @return 底层数组；可变；线程不安全。
     * @throws UnsupportedOperationException 当底层缓冲区不是堆内数组实现时抛出。
     */
    public byte[] array() {
        return buffer.array();
    }

    /**
     * 返回切片在底层数组中的起始偏移。
     *
     * @return 数组偏移。
     * @throws UnsupportedOperationException 当底层缓冲区不是堆内数组实现时抛出。
     */
    public int arrayOffset() {
        return buffer.arrayOffset() + offset;
    }

    /**
     * 判断底层是否为 direct memory。
     *
     * @return true 表示 direct memory；线程安全。
     */
    public boolean isDirect() {
        return buffer.isDirect();
    }

    /**
     * 判断底层是否为手动 native memory。
     *
     * @return true 表示 native memory；线程安全。
     */
    public boolean isNativeMemory() {
        return buffer.isNativeMemory();
    }

    /**
     * 读取切片内指定位置的字节。
     *
     * @param index 切片内相对位置。
     * @return 字节值。
     * @throws IndexOutOfBoundsException 当位置越界时抛出。
     */
    public byte getByte(final int index) {
        Objects.checkIndex(index, length);
        return buffer.getByte(offset + index);
    }

    /**
     * 将切片复制到目标数组。
     *
     * @param target 目标数组；不可为空。
     * @param targetOffset 目标偏移。
     * @throws NullPointerException 当目标数组为空时抛出。
     * @throws IndexOutOfBoundsException 当目标范围越界时抛出。
     */
    public void copyTo(final byte[] target, final int targetOffset) {
        buffer.getBytes(offset, target, targetOffset, length);
    }

    /**
     * 返回切片副本。
     *
     * @return 字节数组副本；不可为空；有序；可能为空；线程安全。
     */
    public byte[] toByteArray() {
        return buffer.toByteArray(offset, length);
    }

    /**
     * 返回只读 ByteBuffer 视图。
     *
     * @return 只读 ByteBuffer；不可为空；position 为 0，limit 为切片长度。
     */
    public ByteBuffer toByteBuffer() {
        return buffer.toByteBuffer(offset, length);
    }

    /**
     * 将切片按字符集解码为字符串。
     *
     * @param charset 字符集；不可为空。
     * @return 解码字符串；不可为空。
     * @throws NullPointerException 当字符集为空时抛出。
     */
    public String decodeString(final Charset charset) {
        return buffer.decodeString(offset, length, charset);
    }

    /**
     * 返回调试描述。
     *
     * @return 调试描述；不可为空。
     */
    @Override
    public String toString() {
        return "ZeroBufferSlice[offset=" + offset + ", length=" + length + "]";
    }
}
