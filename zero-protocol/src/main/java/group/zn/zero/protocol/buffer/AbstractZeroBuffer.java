package group.zn.zero.protocol.buffer;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * zeroServer 协议缓冲区基础实现。
 *
 * <p>该类提供通用边界检查、数组副本、只读 ByteBuffer 副本和重叠 copy 的保守实现。
 * 具体缓冲区可以覆盖热点方法以减少复制。所有方法默认线程不安全。
 *
 * @author zn
 */
public abstract class AbstractZeroBuffer implements ZeroBuffer {

    /**
     * 创建基础缓冲区。
     */
    protected AbstractZeroBuffer() {
    }

    /**
     * 检查指定范围是否落在当前容量内。
     *
     * @param index 起始位置。
     * @param length 长度。
     * @throws IndexOutOfBoundsException 当范围非法时抛出。
     */
    protected final void checkRange(final int index, final int length) {
        Objects.checkFromIndexSize(index, length, capacity());
    }

    /**
     * 计算扩容后的容量。
     *
     * @param currentCapacity 当前容量。
     * @param requiredCapacity 目标容量。
     * @return 新容量。
     * @throws IllegalArgumentException 当目标容量为负数时抛出。
     * @throws OutOfMemoryError 当目标容量溢出 int 正数范围时抛出。
     */
    protected final int expandedCapacity(final int currentCapacity, final int requiredCapacity) {
        if (requiredCapacity < 0) {
            throw new IllegalArgumentException("requiredCapacity must not be negative");
        }
        int newCapacity = Math.max(1, currentCapacity);
        while (newCapacity < requiredCapacity) {
            int next = newCapacity << 1;
            if (next <= 0) {
                newCapacity = requiredCapacity;
                break;
            }
            newCapacity = Math.max(next, requiredCapacity);
        }
        return newCapacity;
    }

    /**
     * 默认通过逐字节移动处理重叠 copy。
     *
     * @param sourceIndex 来源起始位置。
     * @param targetIndex 目标起始位置。
     * @param length 移动长度。
     */
    @Override
    public void copy(final int sourceIndex, final int targetIndex, final int length) {
        checkRange(sourceIndex, length);
        checkRange(targetIndex, length);
        if (length <= 0 || sourceIndex == targetIndex) {
            return;
        }
        if (sourceIndex < targetIndex && sourceIndex + length > targetIndex) {
            for (int offset = length - 1; offset >= 0; offset--) {
                putByte(targetIndex + offset, getByte(sourceIndex + offset));
            }
            return;
        }
        for (int offset = 0; offset < length; offset++) {
            putByte(targetIndex + offset, getByte(sourceIndex + offset));
        }
    }

    /**
     * 默认复制指定范围为新数组。
     *
     * @param index 起始位置。
     * @param length 复制长度。
     * @return 字节数组副本。
     */
    @Override
    public byte[] toByteArray(final int index, final int length) {
        checkRange(index, length);
        byte[] bytes = new byte[length];
        getBytes(index, bytes, 0, length);
        return bytes;
    }

    /**
     * 默认返回基于数组副本的只读 ByteBuffer。
     *
     * @param index 起始位置。
     * @param length 视图长度。
     * @return 只读 ByteBuffer。
     */
    @Override
    public ByteBuffer toByteBuffer(final int index, final int length) {
        return ByteBuffer.wrap(toByteArray(index, length)).asReadOnlyBuffer();
    }

    /**
     * 默认通过堆内副本解码字符串。
     *
     * @param index 起始位置。
     * @param length 字节长度。
     * @param charset 字符集；不可为空。
     * @return 解码字符串。
     */
    @Override
    public String decodeString(final int index, final int length, final Charset charset) {
        Objects.requireNonNull(charset, "charset");
        return new String(toByteArray(index, length), charset);
    }

    /**
     * 默认不暴露堆内数组。
     *
     * @return false。
     */
    @Override
    public boolean hasArray() {
        return false;
    }

    /**
     * 默认没有堆内数组。
     *
     * @return 不会正常返回。
     * @throws UnsupportedOperationException 始终抛出。
     */
    @Override
    public byte[] array() {
        throw new UnsupportedOperationException("buffer does not expose a heap array");
    }

    /**
     * 默认没有堆内数组偏移。
     *
     * @return 不会正常返回。
     * @throws UnsupportedOperationException 始终抛出。
     */
    @Override
    public int arrayOffset() {
        throw new UnsupportedOperationException("buffer does not expose a heap array");
    }

    /**
     * 默认不是 DirectByteBuffer。
     *
     * @return false。
     */
    @Override
    public boolean isDirect() {
        return false;
    }

    /**
     * 默认不是手动 native memory。
     *
     * @return false。
     */
    @Override
    public boolean isNativeMemory() {
        return false;
    }

    /**
     * 默认释放动作为 no-op。
     */
    @Override
    public void close() {
        // 默认无资源需要释放。
    }
}
