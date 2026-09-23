package group.zn.zero.protocol.buffer;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 中立的只读字节借用适配，不复制输入且不暴露可写存储。
 * 游标线程独占；底层内容的稳定性由输入所有者保证，ProtocolFrame 视图可安全保留。
 * @author zn
 */
final class ReadOnlyZeroBuffer extends AbstractZeroBuffer {
    /** 私有只读切片，独立于调用者的 position/limit。 */
    private final ByteBuffer source;
    /** 框架可直接提供私有数组；不经公开 array()/slice 暴露可写引用。 */
    private final byte[] bytes;

    ReadOnlyZeroBuffer(final ByteBuffer source) {
        this.source = Objects.requireNonNull(source, "source").slice().asReadOnlyBuffer();
        this.bytes = null;
    }

    ReadOnlyZeroBuffer(final byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        this.source = null;
    }

    /** @return 固定可读容量；只读，线程安全。 */
    @Override public int capacity() { return bytes == null ? source.capacity() : bytes.length; }
    /** @param index 读取位置。 @return 字节；越界时抛出 IndexOutOfBoundsException。 */
    @Override public byte getByte(final int index) { return bytes == null ? source.get(index) : bytes[index]; }
    /** @param index 起点。 @param target 可变目标。 @param offset 目标偏移。 @param length 长度；只改目标。 */
    @Override public void getBytes(final int index, final byte[] target, final int offset, final int length) {
        if (bytes == null) source.get(index, target, offset, length);
        else System.arraycopy(bytes, index, target, offset, length);
    }
    /** @param index 起点。 @param length 长度。 @return 独立游标的只读借用视图，内容有序、可能为空。 */
    @Override public ByteBuffer toByteBuffer(final int index, final int length) {
        checkRange(index, length);
        return bytes == null ? source.slice(index, length)
                : ByteBuffer.wrap(bytes, index, length).slice().asReadOnlyBuffer();
    }
    /** @param index 起点。 @param length 长度。 @param charset 字符集。 @return 独立字符串，不暴露内部字节。 */
    @Override public String decodeString(final int index, final int length, final Charset charset) {
        checkRange(index, length);
        // 自定义 CharsetDecoder 可能访问输入数组；仅 JDK 的固定 UTF-8 实例可读私有存储。
        return bytes != null && charset == StandardCharsets.UTF_8
                ? new String(bytes, index, length, charset) : super.decodeString(index, length, charset);
    }
    /** @return 是否来自 direct memory；只读。 */
    @Override public boolean isDirect() { return bytes == null && source.isDirect(); }
    /** @param required 目标容量。 @throws ReadOnlyBufferException 始终拒绝可写用途，包括扩容。 */
    @Override public void ensureCapacity(final int required) { throw new ReadOnlyBufferException(); }
    /** @param index 位置。 @param value 字节。 @throws ReadOnlyBufferException 始终拒绝写入。 */
    @Override public void putByte(final int index, final byte value) { throw new ReadOnlyBufferException(); }
    /** @param index 位置。 @param bytes 数据。 @param offset 偏移。 @param length 长度。 @throws ReadOnlyBufferException 始终拒绝。 */
    @Override public void putBytes(final int index, final byte[] bytes, final int offset, final int length) {
        throw new ReadOnlyBufferException();
    }
    /** @param index 位置。 @param bytes 数据。 @throws ReadOnlyBufferException 始终拒绝。 */
    @Override public void putBytes(final int index, final ByteBuffer bytes) { throw new ReadOnlyBufferException(); }
    /** @param from 源位置。 @param to 目标位置。 @param length 长度。 @throws ReadOnlyBufferException 始终拒绝。 */
    @Override public void copy(final int from, final int to, final int length) { throw new ReadOnlyBufferException(); }
}
