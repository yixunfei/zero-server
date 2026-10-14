package group.zn.zero.benchmark.performance;

import group.zn.zero.ranking.LocalRankingService;
import java.util.TreeSet;

/** 基准专用评分序号协调器：只归档连续已完成范围，不能跳过其他线程的在途写入。 @author zn */
final class RankingBenchmarkRequests {
    /** 分配水位。 */
    private long allocated;
    /** 连续已完成水位。 */
    private long completed;
    /** 已向服务确认的水位。 */
    private long acknowledged;
    /** 乱序完成的序号；仅持锁访问，缺口被填补后立即释放。 */
    private final TreeSet<Long> outOfOrder = new TreeSet<>();

    RankingBenchmarkRequests(final long initial) { allocated = initial; completed = initial; acknowledged = initial; }

    synchronized long next() { return ++allocated; }

    synchronized void complete(final long sequence, final LocalRankingService service, final String board) {
        outOfOrder.add(sequence);
        while (outOfOrder.remove(completed + 1)) completed++;
        if (completed - acknowledged >= 1024) {
            service.acknowledgeRequestsThrough(board, "s", completed);
            acknowledged = completed;
        }
    }
}
