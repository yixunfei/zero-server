package group.zn.zero.protocol.codec;

import group.zn.zero.protocol.buffer.ZeroWriter;

/**
 * 同步编码的临时 writer 缓存。每个平台线程最多保留一个 64 KiB 堆缓冲，虚拟线程不缓存。
 * 借出时移除缓存槽，嵌套编码获得独立 writer；结果必须复制后再归还，不允许视图逃逸。
 * 该类不池化对外的 ZeroBuffer，也不引入引用计数或 native 内存池。
 * @author zn
 */
final class EncodingWriters {
    /** 单个平台线程最大保留容量；超大消息只临时分配。 */
    private static final int MAX_RETAINED_CAPACITY = 64 * 1024;
    /** 每个平台线程的空闲 writer；借出期间为 null。 */
    private static final ThreadLocal<ZeroWriter> IDLE = new ThreadLocal<>();

    private EncodingWriters() { }

    /**
     * 借用独占 writer；线程安全，变更当前线程缓存。
     * @param capacity 初始所需容量；非负。
     * @return 写入位置为零的独占 writer；必须在同一线程 finally 归还。
     * @throws IllegalArgumentException 容量为负数。
     */
    static ZeroWriter acquire(final int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("capacity must not be negative");
        if (Thread.currentThread().isVirtual() || capacity > MAX_RETAINED_CAPACITY) {
            return new ZeroWriter(capacity);
        }
        ZeroWriter writer = IDLE.get();
        if (writer == null) return new ZeroWriter(capacity);
        IDLE.set(null);
        writer.buffer().ensureCapacity(capacity);
        return writer;
    }

    /**
     * 归还借出的 writer，清空逻辑长度但不擦除字节；线程安全，变更当前线程缓存。
     * @param writer 本线程借出的 writer；不可为空，归还后不得使用 writer 或其借用视图。
     */
    static void release(final ZeroWriter writer) {
        if (!Thread.currentThread().isVirtual() && writer.capacity() <= MAX_RETAINED_CAPACITY) {
            writer.reset();
            ZeroWriter idle = IDLE.get();
            if (idle == null || idle.capacity() < writer.capacity()) IDLE.set(writer);
        }
        // 仅缓存堆内 writer；未保留的对象由 GC 回收，不借用外部/native 缓冲。
    }
}
