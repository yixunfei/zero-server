package group.zn.zero.benchmark.performance;

import group.zn.zero.ranking.LocalRankingService;
import group.zn.zero.ranking.ScoreMergeMode;
import group.zn.zero.ranking.SeasonState;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.ThreadParams;

/** 共享服务的同榜/多榜查询和写入，不能使用线程私有榜单推断锁竞争。 @author zn */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SharedRankingBenchmark {
    /** 每榜条目数。 */
    @Param({"100", "10000"}) public int size;
    /** 榜单数；线程按编号分配。 */
    @Param({"1", "8"}) public int boards;
    /** 写比例百分比。 */
    @Param({"0", "10", "100"}) public int writes;
    /** 共享服务。 */
    private LocalRankingService service;
    /** 预构造目录 ID。 */
    private String[] names;
    /** 初始化所有共享榜单；setup 不参与测量。 */
    @Setup public void setup() {
        service = new LocalRankingService();
        names = new String[boards];
        for (int board = 0; board < boards; board++) {
            names[board] = "r" + board;
            service.transitionSeason(names[board], "s", SeasonState.OPEN, "open");
            for (int i = 0; i < size; i++) {
                service.submitScore(names[board], "s", "u" + i, i % 100, i % 7, ScoreMergeMode.SET, "initial");
            }
        }
    }
    /** 每线程输入计数，服务仍共享。 @author zn */
    @State(Scope.Thread)
    public static class Input {
        /** 非幂等版本。 */
        private long sequence;
        /** 独立的写入 UID。 */
        private String uid;
        /** @param thread JMH 线程编号；初始化线程输入。 */
        @Setup public void setup(final ThreadParams thread) { uid = "writer" + thread.getThreadIndex(); }
    }
    /** @param input 独立输入。 @param thread JMH 线程。 @return 精确完成结果。 */
    @Benchmark public Object mixed(final Input input, final ThreadParams thread) {
        String board = names[thread.getThreadIndex() % boards];
        long sequence = ++input.sequence;
        return sequence % 100 < writes
                ? service.submitScore(board, "s", input.uid, sequence, 0, ScoreMergeMode.SET, Long.toString(sequence))
                : service.queryTop(board, "s", 10);
    }
}
