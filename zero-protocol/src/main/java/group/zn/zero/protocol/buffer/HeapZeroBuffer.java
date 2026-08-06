package group.zn.zero.protocol.buffer;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * 基于堆内 byte[] 的协议缓冲区。
 *
 * <p>这是 `ZeroReader` 与 `ZeroWriter` 的默认实现，适合作为协议编解码热路径基线。
 * 扩容时会创建更大的堆内数组并复制已有内容。所有方法线程不安全。
 *
 * @author zn
 */
public final class HeapZeroBuffer extends AbstractZeroBuffer {

    /**
     * 底层堆内字节数组。
     */
    private byte[] bytes;

    /**
     * 创建指定容量的堆内缓冲区。
     *
     * @param initialCapacity 初始容量。
     * @throws IllegalArgumentException 当容量为负数时抛出。
     */
    public HeapZeroBuffer(final int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        this.bytes = new byte[Math.max(1, initialCapacity)];
    }

    /**
     * 借用指定堆内数组作为缓冲区。
     *
     * @param bytes 底层数组；不可为空。
     * @throws NullPointerException 当数组为空时抛出。
     */
    public HeapZeroBuffer(final byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
    }

    /**
     * 返回当前容量。
     *
     * @return 数组长度。
     */
    @Override
    public int capacity() {
        return bytes.length;
    }

    /**
     * 确保底层数组容量足够。
     *
     * @param requiredCapacity 目标容量。
     */
    @Override
    public void ensureCapacity(final int requiredCapacity) {
        if (requiredCapacity < 0) {
            throw new IllegalArgumentException("requiredCapacity must not be negative");
        }
        if (requiredCapacity <= bytes.length) {
            return;
        }
        bytes = Arrays.copyOf(bytes, expandedCapacity(bytes.length, requiredCapacity));
    }

    /**
     * 读取单字节。
     *
     * @param index 读取位置。
     * @return 字节值。
     */
    @Override
    public byte getByte(final int index) {
        return bytes[index];
    }

    /**
     * 写入单字节。
     *
     * @param index 写入位置。
     * @param value 字节值。
     */
    @Override
    public void putByte(final int index, final byte value) {
        bytes[index] = value;
    }

    /**
     * 复制字节到目标数组。
     *
     * @param index 缓冲区起始位置。
     * @param target 目标数组；不可为空。
     * @param targetOffset 目标数组起始偏移。
     * @param length 复制长度。
     */
    @Override
    public void getBytes(final int index, final byte[] target, final int targetOffset, final int length) {
        Objects.checkFromIndexSize(index, length, bytes.length);
        Objects.checkFromIndexSize(targetOffset, length, Objects.requireNonNull(target, "target").length);
        System.arraycopy(bytes, index, target, targetOffset, length);
    }

    /**
     * 从来源数组复制字节。
     *
     * @param index 缓冲区起始位置。
     * @param source 来源数组；不可为空。
     * @param sourceOffset 来源数组起始偏移。
     * @param length 复制长度。
     */
    @Override
    public void putBytes(final int index, final byte[] source, final int sourceOffset, final int length) {
        Objects.checkFromIndexSize(index, length, bytes.length);
        Objects.checkFromIndexSize(sourceOffset, length, Objects.requireNonNull(source, "source").length);
        System.arraycopy(source, sourceOffset, bytes, index, length);
    }

    /**
     * 在堆内数组内部移动字节。
     *
     * @param sourceIndex 来源起始位置。
     * @param targetIndex 目标起始位置。
     * @param length 移动长度。
     */
    @Override
    public void copy(final int sourceIndex, final int targetIndex, final int length) {
        Objects.checkFromIndexSize(sourceIndex, length, bytes.length);
        Objects.checkFromIndexSize(targetIndex, length, bytes.length);
        System.arraycopy(bytes, sourceIndex, bytes, targetIndex, length);
    }

    /**
     * 返回指定范围的数组副本。
     *
     * @param index 起始位置。
     * @param length 复制长度。
     * @return 字节数组副本。
     */
    @Override
    public byte[] toByteArray(final int index, final int length) {
        Objects.checkFromIndexSize(index, length, bytes.length);
        return Arrays.copyOfRange(bytes, index, index + length);
    }

    /**
     * 返回指定范围的只读 ByteBuffer 借用视图。
     *
     * @param index 起始位置。
     * @param length 视图长度。
     * @return 只读 ByteBuffer。
     */
    @Override
    public ByteBuffer toByteBuffer(final int index, final int length) {
        Objects.checkFromIndexSize(index, length, bytes.length);
        return ByteBuffer.wrap(bytes, index, length).slice().asReadOnlyBuffer();
    }

    /**
     * 从堆内数组范围解码字符串。
     *
     * @param index 起始位置。
     * @param length 字节长度。
     * @param charset 字符集；不可为空。
     * @return 解码字符串。
     */
    @Override
    public String decodeString(final int index, final int length, final Charset charset) {
        Objects.checkFromIndexSize(index, length, bytes.length);
        return new String(bytes, index, length, Objects.requireNonNull(charset, "charset"));
    }

    /**
     * 判断是否暴露堆内数组。
     *
     * @return true。
     */
    @Override
    public boolean hasArray() {
        return true;
    }

    /**
     * 返回底层堆内数组。
     *
     * @return 底层数组；可变。
     */
    @Override
    public byte[] array() {
        return bytes;
    }

    /**
     * 返回底层数组偏移。
     *
     * @return 0。
     */
    @Override
    public int arrayOffset() {
        return 0;
    }
}
