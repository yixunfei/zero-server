package group.zn.zero.net.netty;

import java.util.concurrent.atomic.AtomicLong;

/** 待写字节的精确准入计数；不用于统计近似，线程安全。 @author zn */
final class OutboundBudget {
    /** 最大许可。 */
    private final long maximum;
    /** 已占用许可。 */
    private final AtomicLong used = new AtomicLong();
    OutboundBudget(final long maximum) { this.maximum = maximum; }
    boolean acquire(final long bytes) {
        long previous;
        do {
            previous = used.get();
            if (bytes < 0 || bytes > maximum - previous) return false;
        } while (!used.compareAndSet(previous, previous + bytes));
        return true;
    }
    void release(final long bytes) { used.addAndGet(-bytes); }
    long used() { return used.get(); }
}
