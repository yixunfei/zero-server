package group.zn.zero.net.kcp;

import java.util.concurrent.atomic.AtomicLong;

/** 跨调用线程预留发送预算，防止 EventLoop 提交队列绕过背压；线程安全。 @author zn */
final class KcpBudget {
    /** 本服务共享字节计数。 */
    private final AtomicLong total;
    /** 单连接字节计数。 */
    private final AtomicLong local = new AtomicLong();
    /** 服务字节上限。 */
    private final long totalLimit;
    /** 连接字节上限。 */
    private final long localLimit;

    KcpBudget(final AtomicLong total, final long totalLimit, final long localLimit) {
        this.total = total;
        this.totalLimit = totalLimit;
        this.localLimit = localLimit;
    }
    boolean reserve(final long bytes) {
        if (!reserve(local, localLimit, bytes)) return false;
        if (reserve(total, totalLimit, bytes)) return true;
        local.addAndGet(-bytes);
        return false;
    }
    void release(final long bytes) {
        local.addAndGet(-bytes);
        total.addAndGet(-bytes);
    }
    long pending() { return local.get(); }
    static boolean reserve(final AtomicLong counter, final long limit, final long bytes) {
        long current;
        do {
            current = counter.get();
            if (bytes <= 0 || bytes > limit - current) return false;
        } while (!counter.compareAndSet(current, current + bytes));
        return true;
    }
}
