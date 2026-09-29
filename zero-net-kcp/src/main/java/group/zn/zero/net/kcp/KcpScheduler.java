package group.zn.zero.net.kcp;

import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 每会话恰好一个截止记录、每服务一个计时器；仅 EventLoop 访问。 @author zn */
final class KcpScheduler {
    /** 单轮工作上限，防止重传饿死 socket 读事件。 */
    private static final int BATCH = 64;
    /** 所属服务。 */
    private final KcpServer owner;
    /** 索引便于 O(log n) 移除；没有惰性失效记录。 */
    private final Map<KcpSession, Due> index = new IdentityHashMap<>();
    /** 到期顺序。 */
    private final TreeSet<Due> queue = new TreeSet<>(Comparator.comparingLong(Due::time)
            .thenComparingInt(item -> item.session().ticket.conv()));
    /** 唯一等待计时器。 */
    private ScheduledFuture<?> timer;
    /** 当前计时器期限。 */
    private long armed;
    KcpScheduler(final KcpServer owner) { this.owner = owner; }
    void schedule(final KcpSession session) {
        Due due = index.get(session);
        if (due != null) queue.remove(due);
        if (!session.connection.isClosed()) {
            if (due == null) { due = new Due(session); index.put(session, due); }
            // 仅在移出排序容器后修改键，每个会话复用一个记录。
            due.time = session.nextDeadline(System.nanoTime());
            queue.add(due);
        } else index.remove(session);
        arm();
    }
    void remove(final KcpSession session) {
        Due old = index.remove(session);
        if (old != null) queue.remove(old);
    }
    void close() {
        if (timer != null) timer.cancel(false);
        timer = null;
        queue.clear();
        index.clear();
    }
    private void arm() {
        if (queue.isEmpty()) {
            if (timer != null) timer.cancel(false);
            timer = null;
            return;
        }
        long next = queue.first().time();
        if (timer != null && armed <= next) return;
        if (timer != null) timer.cancel(false);
        armed = next;
        timer = owner.channel().eventLoop().schedule(this::run,
                Math.max(1, next - System.nanoTime()), TimeUnit.NANOSECONDS);
    }
    private void run() {
        timer = null;
        for (int count = 0; count < BATCH && !queue.isEmpty(); count++) {
            Due due = queue.first();
            long now = System.nanoTime();
            if (due.time() > now) break;
            queue.remove(due);
            try { due.session().tick(now); }
            catch (RuntimeException failure) {
                owner.fail(due.session(), group.zn.zero.net.error.NetErrorCode.SEND_FAILED, failure);
            }
            schedule(due.session());
        }
        arm();
    }
    /** 复用截止记录；排序键只在离开 TreeSet 后修改。 @author zn */
    private static final class Due {
        /** 截止时间。 */
        private long time;
        /** 固定会话。 */
        private final KcpSession session;
        Due(final KcpSession session) { this.session = session; }
        long time() { return time; }
        KcpSession session() { return session; }
    }
}
