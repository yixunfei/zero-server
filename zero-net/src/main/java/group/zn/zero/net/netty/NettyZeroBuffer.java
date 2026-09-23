package group.zn.zero.net.netty;

import group.zn.zero.protocol.buffer.AbstractZeroBuffer;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * 仅在 codec 调用栈借用 ByteBuf 的协议适配；不 retain/release，不允许跨异步边界持有。
 * 游标由 ZeroReader/ZeroWriter 管理，ByteBuf 生命周期属于 Netty pipeline。线程不安全。
 * @author zn
 */
final class NettyZeroBuffer extends AbstractZeroBuffer {
    /** 被借用缓冲。 */
    private final ByteBuf buffer;
    /** 同步借用期间的单段搬移视图；扩容即失效，不逃逸至业务。 */
    private ByteBuffer moveView;
    /** 输出上限，扩容前检查。 */
    private final int maximum;
    NettyZeroBuffer(final ByteBuf buffer, final int maximum) {
        this.buffer = buffer;
        this.maximum = maximum;
    }
    @Override public int capacity() { return buffer.capacity(); }
    @Override public void ensureCapacity(final int required) {
        if (required < 0 || required > maximum) throw new IllegalArgumentException("encoded frame exceeds capacity");
        if (required > buffer.capacity()) {
            buffer.capacity(required);
            moveView = null;
        }
    }
    @Override public byte getByte(final int index) { return buffer.getByte(index); }
    @Override public void putByte(final int index, final byte value) { buffer.setByte(index, value); }
    @Override public void getBytes(final int index, final byte[] target, final int offset, final int length) {
        buffer.getBytes(index, target, offset, length);
    }
    @Override public void putBytes(final int index, final byte[] source, final int offset, final int length) {
        buffer.setBytes(index, source, offset, length);
    }
    @Override public void putBytes(final int index, final ByteBuffer source) {
        int position = source.position();
        try { buffer.setBytes(index, source); }
        finally { source.position(position); }
    }
    @Override public String decodeString(final int index, final int length, final Charset charset) {
        return buffer.toString(index, length, charset);
    }
    @Override public boolean isDirect() { return buffer.isDirect(); }

    /** 同一借用缓冲内重叠搬移；单段内存使用 JDK 批量语义，组合缓冲保留通用路径。 */
    @Override public void copy(final int source, final int target, final int length) {
        checkRange(source, length);
        checkRange(target, length);
        if (buffer.isReadOnly()) throw new java.nio.ReadOnlyBufferException();
        if (buffer.hasArray()) {
            System.arraycopy(buffer.array(), buffer.arrayOffset() + source,
                    buffer.array(), buffer.arrayOffset() + target, length);
        } else if (buffer.nioBufferCount() == 1) {
            if (moveView == null || moveView.capacity() != buffer.capacity()) {
                moveView = buffer.nioBuffer(0, buffer.capacity());
            }
            moveView.put(target, moveView, source, length);
        } else {
            super.copy(source, target, length);
        }
    }
}
