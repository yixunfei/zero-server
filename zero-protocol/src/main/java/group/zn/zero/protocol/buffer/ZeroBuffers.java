package group.zn.zero.protocol.buffer;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * 协议缓冲区工厂。
 *
 * <p>默认业务应优先使用 heap 缓冲区；direct 与 native memory 需要结合场景和 benchmark 显式选择。
 * 本工厂不依赖 Netty 或具体 IO 框架。
 *
 * @author zn
 */
public final class ZeroBuffers {

    /**
     * 禁止实例化。
     */
    private ZeroBuffers() {
    }

    /**
     * 创建堆内缓冲区。
     *
     * @param initialCapacity 初始容量。
     * @return 堆内缓冲区；不可为空；调用方独占。
     */
    public static ZeroBuffer heap(final int initialCapacity) {
        return new HeapZeroBuffer(initialCapacity);
    }

    /**
     * 借用堆内数组作为缓冲区。
     *
     * @param bytes 底层数组；不可为空。
     * @return 堆内缓冲区；不可为空；线程不安全。
     * @throws NullPointerException 当数组为空时抛出。
     */
    public static ZeroBuffer wrap(final byte[] bytes) {
        return new HeapZeroBuffer(Objects.requireNonNull(bytes, "bytes"));
    }

    /**
     * 创建 DirectByteBuffer 缓冲区。
     *
     * @param initialCapacity 初始容量。
     * @return direct 缓冲区；不可为空；调用方独占。
     */
    public static ZeroBuffer direct(final int initialCapacity) {
        return new DirectZeroBuffer(initialCapacity);
    }

    /**
     * 借用 ByteBuffer 的 remaining 区间作为缓冲区。
     *
     * <p>direct ByteBuffer 会零拷贝借用；非 direct ByteBuffer 会复制为堆内缓冲区。
     *
     * @param byteBuffer ByteBuffer；不可为空。
     * @return 协议缓冲区；不可为空；线程不安全。
     * @throws NullPointerException 当 ByteBuffer 为空时抛出。
     */
    public static ZeroBuffer wrap(final ByteBuffer byteBuffer) {
        ByteBuffer source = Objects.requireNonNull(byteBuffer, "byteBuffer").slice();
        if (source.isDirect()) {
            return new DirectZeroBuffer(source);
        }
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return new HeapZeroBuffer(bytes);
    }

    /**
     * 创建 native memory 缓冲区。
     *
     * @param initialCapacity 初始容量。
     * @return native memory 缓冲区；不可为空；调用方必须释放。
     * @throws UnsupportedOperationException 当 Unsafe 不可用时抛出。
     */
    public static ZeroBuffer nativeMemory(final int initialCapacity) {
        return new NativeMemoryZeroBuffer(initialCapacity);
    }

    /**
     * 判断当前环境是否支持 native memory 缓冲区。
     *
     * @return true 表示 Unsafe 可用；线程安全。
     */
    public static boolean nativeMemoryAvailable() {
        return ZeroUnsafe.available();
    }
}
