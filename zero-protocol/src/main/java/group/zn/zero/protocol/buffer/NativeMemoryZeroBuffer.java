package group.zn.zero.protocol.buffer;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.Objects;
import sun.misc.Unsafe;

/**
 * 基于手动 native memory 的协议缓冲区。
 *
 * <p>该实现使用 Unsafe 分配和释放 native memory，适合作为后续极限性能实验的基础实现。
 * 它不是默认实现。调用方应优先使用 try-with-resources 或显式 close 释放内存。所有方法线程不安全。
 *
 * @author zn
 */
public final class NativeMemoryZeroBuffer extends AbstractZeroBuffer {

    /**
     * native memory 清理器。
     */
    private static final Cleaner CLEANER = Cleaner.create();

    /**
     * Unsafe 实例。
     */
    private static final Unsafe UNSAFE = ZeroUnsafe.unsafe();

    /**
     * native memory 地址。
     */
    private long address;

    /**
     * 当前容量。
     */
    private int capacity;

    /**
     * 是否已经关闭。
     */
    private boolean closed;

    /**
     * Cleaner 清理状态。
     */
    private final NativeMemoryCleanup cleanup;

    /**
     * Cleaner 句柄。
     */
    private final Cleaner.Cleanable cleanable;

    /**
     * 创建指定容量的 native memory 缓冲区。
     *
     * @param initialCapacity 初始容量。
     * @throws IllegalArgumentException 当容量为负数时抛出。
     * @throws UnsupportedOperationException 当 Unsafe 不可用时抛出。
     */
    public NativeMemoryZeroBuffer(final int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must not be negative");
        }
        int actualCapacity = Math.max(1, initialCapacity);
        long allocatedAddress = UNSAFE.allocateMemory(actualCapacity);
        this.address = allocatedAddress;
        this.capacity = actualCapacity;
        this.cleanup = new NativeMemoryCleanup(allocatedAddress);
        this.cleanable = CLEANER.register(this, cleanup);
    }

    /**
     * 返回当前容量。
     *
     * @return native memory 容量。
     */
    @Override
    public int capacity() {
        checkOpen();
        return capacity;
    }

    /**
     * 确保 native memory 容量足够。
     *
     * @param requiredCapacity 目标容量。
     */
    @Override
    public void ensureCapacity(final int requiredCapacity) {
        checkOpen();
        if (requiredCapacity < 0) {
            throw new IllegalArgumentException("requiredCapacity must not be negative");
        }
        if (requiredCapacity <= capacity) {
            return;
        }
        int newCapacity = expandedCapacity(capacity, requiredCapacity);
        long newAddress = UNSAFE.allocateMemory(newCapacity);
        UNSAFE.copyMemory(null, address, null, newAddress, capacity);
        long oldAddress = address;
        address = newAddress;
        capacity = newCapacity;
        cleanup.address = newAddress;
        UNSAFE.freeMemory(oldAddress);
    }

    /**
     * 读取单字节。
     *
     * @param index 读取位置。
     * @return 字节值。
     */
    @Override
    public byte getByte(final int index) {
        checkOpen();
        Objects.checkIndex(index, capacity);
        return UNSAFE.getByte(address + index);
    }

    /**
     * 写入单字节。
     *
     * @param index 写入位置。
     * @param value 字节值。
     */
    @Override
    public void putByte(final int index, final byte value) {
        checkOpen();
        Objects.checkIndex(index, capacity);
        UNSAFE.putByte(address + index, value);
    }

    /**
     * 从 native memory 复制字节到目标数组。
     *
     * @param index 缓冲区起始位置。
     * @param target 目标数组；不可为空。
     * @param targetOffset 目标数组起始偏移。
     * @param length 复制长度。
     */
    @Override
    public void getBytes(final int index, final byte[] target, final int targetOffset, final int length) {
        checkOpen();
        checkRange(index, length);
        Objects.checkFromIndexSize(targetOffset, length, Objects.requireNonNull(target, "target").length);
        UNSAFE.copyMemory(null, address + index, target, ZeroUnsafe.BYTE_ARRAY_OFFSET + targetOffset, length);
    }

    /**
     * 从来源数组复制字节到 native memory。
     *
     * @param index 缓冲区起始位置。
     * @param source 来源数组；不可为空。
     * @param sourceOffset 来源数组起始偏移。
     * @param length 复制长度。
     */
    @Override
    public void putBytes(final int index, final byte[] source, final int sourceOffset, final int length) {
        checkOpen();
        checkRange(index, length);
        Objects.checkFromIndexSize(sourceOffset, length, Objects.requireNonNull(source, "source").length);
        UNSAFE.copyMemory(source, ZeroUnsafe.BYTE_ARRAY_OFFSET + sourceOffset, null, address + index, length);
    }

    /**
     * 在 native memory 内部移动字节。
     *
     * @param sourceIndex 来源起始位置。
     * @param targetIndex 目标起始位置。
     * @param length 移动长度。
     */
    @Override
    public void copy(final int sourceIndex, final int targetIndex, final int length) {
        checkOpen();
        checkRange(sourceIndex, length);
        checkRange(targetIndex, length);
        if (length <= 0 || sourceIndex == targetIndex) {
            return;
        }
        if (sourceIndex + length <= targetIndex || targetIndex + length <= sourceIndex) {
            UNSAFE.copyMemory(null, address + sourceIndex, null, address + targetIndex, length);
            return;
        }
        super.copy(sourceIndex, targetIndex, length);
    }

    /**
     * 返回基于副本的只读 direct ByteBuffer。
     *
     * @param index 起始位置。
     * @param length 视图长度。
     * @return 只读 direct ByteBuffer。
     */
    @Override
    public ByteBuffer toByteBuffer(final int index, final int length) {
        checkOpen();
        checkRange(index, length);
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(length);
        byte[] copy = toByteArray(index, length);
        byteBuffer.put(copy);
        byteBuffer.flip();
        return byteBuffer.asReadOnlyBuffer();
    }

    /**
     * 判断是否基于手动 native memory。
     *
     * @return true。
     */
    @Override
    public boolean isNativeMemory() {
        return true;
    }

    /**
     * 释放 native memory。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        address = 0L;
        capacity = 0;
        cleanable.clean();
    }

    /**
     * 检查缓冲区是否仍然可用。
     *
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    private void checkOpen() {
        if (closed || address == 0L) {
            throw new IllegalStateException("native memory buffer is closed");
        }
    }

    /**
     * native memory 清理动作。
     */
    private static final class NativeMemoryCleanup implements Runnable {

        /**
         * 待释放 native memory 地址。
         */
        private volatile long address;

        /**
         * 创建清理动作。
         *
         * @param address native memory 地址。
         */
        private NativeMemoryCleanup(final long address) {
            this.address = address;
        }

        /**
         * 释放 native memory。
         */
        @Override
        public void run() {
            long current = address;
            if (current == 0L) {
                return;
            }
            address = 0L;
            UNSAFE.freeMemory(current);
        }
    }
}
