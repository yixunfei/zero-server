package group.zn.zero.protocol.buffer;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * zeroServer 协议缓冲区抽象。
 *
 * <p>缓冲区只负责字节存储和复制，不维护 readerIndex 或 writerIndex。索引由
 * {@link ZeroReader} 与 {@link ZeroWriter} 管理。实现可以基于堆内数组、DirectByteBuffer
 * 或 native memory。所有方法默认线程不安全，调用方必须保证独占访问或自行同步。
 *
 * @author zn
 */
public interface ZeroBuffer extends AutoCloseable {

    /**
     * 返回当前容量。
     *
     * @return 容量字节数；非负；线程不安全。
     */
    int capacity();

    /**
     * 确保容量至少为指定大小。
     *
     * @param requiredCapacity 目标容量；必须非负。
     * @throws IllegalArgumentException 当目标容量为负数时抛出。
     * @throws IllegalStateException 当缓冲区已经释放或无法扩容时抛出。
     */
    void ensureCapacity(int requiredCapacity);

    /**
     * 读取指定位置的单字节。
     *
     * @param index 读取位置。
     * @return 字节值。
     * @throws IndexOutOfBoundsException 当位置越界时抛出。
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    byte getByte(int index);

    /**
     * 写入指定位置的单字节。
     *
     * @param index 写入位置。
     * @param value 字节值。
     * @throws IndexOutOfBoundsException 当位置越界时抛出。
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    void putByte(int index, byte value);

    /**
     * 从缓冲区复制字节到堆内数组。
     *
     * @param index 缓冲区起始位置。
     * @param target 目标数组；不可为空。
     * @param targetOffset 目标数组起始偏移。
     * @param length 复制长度。
     * @throws NullPointerException 当目标数组为空时抛出。
     * @throws IndexOutOfBoundsException 当任一范围越界时抛出。
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    void getBytes(int index, byte[] target, int targetOffset, int length);

    /**
     * 从堆内数组复制字节到缓冲区。
     *
     * @param index 缓冲区起始位置。
     * @param source 来源数组；不可为空。
     * @param sourceOffset 来源数组起始偏移。
     * @param length 复制长度。
     * @throws NullPointerException 当来源数组为空时抛出。
     * @throws IndexOutOfBoundsException 当任一范围越界时抛出。
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    void putBytes(int index, byte[] source, int sourceOffset, int length);

    /**
     * 在同一缓冲区内部移动字节，必须正确处理重叠区域。
     *
     * @param sourceIndex 来源起始位置。
     * @param targetIndex 目标起始位置。
     * @param length 移动长度。
     * @throws IndexOutOfBoundsException 当任一范围越界时抛出。
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    void copy(int sourceIndex, int targetIndex, int length);

    /**
     * 复制指定范围为堆内数组。
     *
     * @param index 起始位置。
     * @param length 复制长度。
     * @return 字节数组副本；不可为空；有序；可能为空；线程安全。
     * @throws IndexOutOfBoundsException 当范围越界时抛出。
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    byte[] toByteArray(int index, int length);

    /**
     * 返回指定范围的只读 ByteBuffer 视图。
     *
     * <p>堆内和 direct 实现可以返回借用视图；native memory 实现允许返回副本视图。
     * 调用方不应依赖该视图与底层内存是否共享，只能把它视为只读输入。
     *
     * @param index 起始位置。
     * @param length 视图长度。
     * @return 只读 ByteBuffer；不可为空；position 为 0，limit 为 length；线程不安全。
     * @throws IndexOutOfBoundsException 当范围越界时抛出。
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    ByteBuffer toByteBuffer(int index, int length);

    /**
     * 将指定范围按字符集解码为字符串。
     *
     * @param index 起始位置。
     * @param length 字节长度。
     * @param charset 字符集；不可为空。
     * @return 解码字符串；不可为空；线程安全性由字符集实现保证。
     * @throws NullPointerException 当字符集为空时抛出。
     * @throws IndexOutOfBoundsException 当范围越界时抛出。
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    String decodeString(int index, int length, Charset charset);

    /**
     * 判断是否可以直接访问底层堆内数组。
     *
     * @return true 表示 {@link #array()} 与 {@link #arrayOffset()} 可用；线程安全。
     */
    boolean hasArray();

    /**
     * 返回底层堆内数组。
     *
     * @return 底层数组；不可为空；可变；线程不安全。
     * @throws UnsupportedOperationException 当缓冲区不是堆内数组实现时抛出。
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    byte[] array();

    /**
     * 返回底层数组偏移。
     *
     * @return 数组偏移；仅当 {@link #hasArray()} 为 true 时有效；线程安全。
     * @throws UnsupportedOperationException 当缓冲区不是堆内数组实现时抛出。
     * @throws IllegalStateException 当缓冲区已经释放时抛出。
     */
    int arrayOffset();

    /**
     * 判断是否基于 DirectByteBuffer。
     *
     * @return true 表示缓冲区位于 direct memory；线程安全。
     */
    boolean isDirect();

    /**
     * 判断是否基于手动 native memory。
     *
     * @return true 表示缓冲区由显式 native memory 管理；线程安全。
     */
    boolean isNativeMemory();

    /**
     * 释放底层资源。
     *
     * <p>堆内和 direct 实现通常为 no-op；native memory 实现会释放内存。释放后再次读写属于非法操作。
     */
    @Override
    void close();
}
