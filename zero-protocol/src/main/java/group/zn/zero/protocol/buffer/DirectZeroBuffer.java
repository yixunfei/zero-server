package group.zn.zero.protocol.buffer;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * 基于 DirectByteBuffer 的协议缓冲区。
 *
 * <p>该实现适合通信 IO 边界、文件通道或压缩器可以直接消费 ByteBuffer 的场景。
 * VarInt 等逐字节热路径未必比堆内数组更快，调用方应根据 benchmark 显式选择。所有方法线程不安全。
 *
 * @author zn
 */
public final class DirectZeroBuffer extends AbstractZeroBuffer {

    /**
     * 底层 direct buffer，使用绝对读写，不依赖 position。
     */
    private ByteBuffer buffer;

    /**
     * 创建指定容量的 direct 缓冲区。
     *
     * @param initialCapacity 初始容量。
     * @throws IllegalArgumentException 当容量为负数时抛出。
     */
    public DirectZeroBuffer(final int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        this.buffer = ByteBuffer.allocateDirect(Math.max(1, initialCapacity));
    }

    /**
     * 借用指定 direct ByteBuffer 的 remaining 区间作为缓冲区。
     *
     * <p>该构造会创建 slice，读写会反映到底层 direct memory；扩容后会脱离原 ByteBuffer。
     *
     * @param byteBuffer direct ByteBuffer；不可为空。
     * @throws NullPointerException 当 ByteBuffer 为空时抛出。
     * @throws IllegalArgumentException 当 ByteBuffer 不是 direct 时抛出。
     */
    public DirectZeroBuffer(final ByteBuffer byteBuffer) {
        ByteBuffer source = Objects.requireNonNull(byteBuffer, "byteBuffer");
        if (!source.isDirect()) {
            throw new IllegalArgumentException("byteBuffer must be direct");
        }
        this.buffer = source.slice();
    }

    /**
     * 返回当前容量。
     *
     * @return direct buffer 容量。
     */
    @Override
    public int capacity() {
        return buffer.capacity();
    }

    /**
     * 确保 direct buffer 容量足够。
     *
     * @param requiredCapacity 目标容量。
     */
    @Override
    public void ensureCapacity(final int requiredCapacity) {
        if (requiredCapacity < 0) {
            throw new IllegalArgumentException("requiredCapacity must not be negative");
        }
        if (requiredCapacity <= buffer.capacity()) {
            return;
        }
        ByteBuffer next = ByteBuffer.allocateDirect(expandedCapacity(buffer.capacity(), requiredCapacity));
        ByteBuffer source = buffer.duplicate();
        source.clear();
        next.put(source);
        next.clear();
        buffer = next;
    }

    /**
     * 读取单字节。
     *
     * @param index 读取位置。
     * @return 字节值。
     */
    @Override
    public byte getByte(final int index) {
        return buffer.get(index);
    }

    /**
     * 写入单字节。
     *
     * @param index 写入位置。
     * @param value 字节值。
     */
    @Override
    public void putByte(final int index, final byte value) {
        buffer.put(index, value);
    }

    /**
     * 从 direct buffer 复制字节到目标数组。
     *
     * @param index 缓冲区起始位置。
     * @param target 目标数组；不可为空。
     * @param targetOffset 目标数组起始偏移。
     * @param length 复制长度。
     */
    @Override
    public void getBytes(final int index, final byte[] target, final int targetOffset, final int length) {
        checkRange(index, length);
        Objects.checkFromIndexSize(targetOffset, length, Objects.requireNonNull(target, "target").length);
        buffer.get(index, target, targetOffset, length);
    }

    /**
     * 从来源数组复制字节到 direct buffer。
     *
     * @param index 缓冲区起始位置。
     * @param source 来源数组；不可为空。
     * @param sourceOffset 来源数组起始偏移。
     * @param length 复制长度。
     */
    @Override
    public void putBytes(final int index, final byte[] source, final int sourceOffset, final int length) {
        checkRange(index, length);
        Objects.checkFromIndexSize(sourceOffset, length, Objects.requireNonNull(source, "source").length);
        buffer.put(index, source, sourceOffset, length);
    }

    /**
     * 使用 JDK 绝对批量搬移；Java 21 保证共享内存的重叠区域按中间副本语义处理。
     * @param sourceIndex 来源起点。
     * @param targetIndex 目标起点。
     * @param length 长度；修改当前缓冲，线程不安全，不改变游标。
     * @throws IndexOutOfBoundsException 任一范围越界。
     */
    @Override public void copy(final int sourceIndex, final int targetIndex, final int length) {
        buffer.put(targetIndex, buffer, sourceIndex, length);
    }

    /**
     * 返回 direct memory 的只读借用视图。
     *
     * @param index 起始位置。
     * @param length 视图长度。
     * @return 只读 direct ByteBuffer。
     */
    @Override
    public ByteBuffer toByteBuffer(final int index, final int length) {
        checkRange(index, length);
        ByteBuffer duplicate = buffer.asReadOnlyBuffer();
        duplicate.position(index);
        duplicate.limit(index + length);
        return duplicate.slice();
    }

    /**
     * 判断是否为 DirectByteBuffer。
     *
     * @return true。
     */
    @Override
    public boolean isDirect() {
        return true;
    }
}
