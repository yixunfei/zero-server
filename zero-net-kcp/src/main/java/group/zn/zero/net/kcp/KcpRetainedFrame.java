package group.zn.zero.net.kcp;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;

/** 最后一个 retainedSlice 释放时回收整帧预算；仅由所属 EventLoop 使用。 @author zn */
final class KcpRetainedFrame extends UnpooledHeapByteBuf {
    /** 整帧存储真正释放后的通知。 */
    private final Runnable released;
    KcpRetainedFrame(final ByteBufAllocator allocator, final byte[] bytes, final Runnable released) {
        super(allocator, bytes, bytes.length);
        this.released = released;
    }
    @Override protected void deallocate() {
        try { super.deallocate(); } finally { released.run(); }
    }
}
