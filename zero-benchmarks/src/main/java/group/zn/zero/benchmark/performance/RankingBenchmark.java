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

/** 排名复杂度基准；只衡量完成的同步操作，参数为条目数。 @author zn */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RankingBenchmark {
    /** 榜单大小。 */
    @Param({"100", "10000", "100000"})
    public int size;
    /** 线程私有榜单。 */
    private LocalRankingService service;
    /** 非幂等更新序号。 */
    private long sequence;

    /** 初始化固定同分分布；每个测量线程独占。 */
    @Setup
    public void setup() {
        service = new LocalRankingService();
        service.transitionSeason("r", "s", SeasonState.OPEN, "open");
        for (int i = 0; i < size; i++) {
            service.submitScore("r", "s", "u" + i, i % 100, i % 7, ScoreMergeMode.SET, "initial");
        }
    }

    /** @return 不可变 Top10 快照；只读，线程私有。 */
    @Benchmark public Object top() { return service.queryTop("r", "s", 10); }
    /** @return 命中排名；只读，线程私有。 */
    @Benchmark public Object rank() { return service.queryPlayerRank("r", "s", "u50"); }
    /** @return 缺失排名；只读，线程私有。 */
    @Benchmark public Object missing() { return service.queryPlayerRank("r", "s", "absent"); }
    /** @return 已完成更新；变更单条分数，线程私有。 */
    @Benchmark public Object update() {
        return service.submitScore("r", "s", "u50", ++sequence % 100, 1, ScoreMergeMode.SET,
                Long.toString(sequence));
    }
}
