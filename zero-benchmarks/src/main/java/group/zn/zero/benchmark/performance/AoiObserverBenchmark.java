package group.zn.zero.benchmark.performance;

import group.zn.zero.aoi.AoiEntity;
import group.zn.zero.aoi.InMemoryAoiIndex;
import group.zn.zero.aoi.Position;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** 多观察者完整轮次，含小比例/全量更新和观察者释放。 @author zn */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class AoiObserverBenchmark {
    /** 实体规模。 */
    @Param({"100", "2000"}) public int size;
    /** 同一场景观察者数。 */
    @Param({"1", "16"}) public int observers;
    /** 每轮更新百分比。 */
    @Param({"0", "1", "100"}) public int updates;
    /** 场景索引。 */
    private InMemoryAoiIndex index;
    /** 可见中心。 */
    private final Position center = new Position(0, 0);
    /** 稳定 ID，避免把测量变为字符串构造基准。 */
    private String[] ids;
    /** 稳定观察者 ID。 */
    private String[] observerIds;
    /** 变更版本。 */
    private long version;
    /** 初始化同一场景中的独立观察者快照。 */
    @Setup public void setup() {
        index = new InMemoryAoiIndex();
        ids = new String[size];
        observerIds = new String[observers];
        for (int i = 0; i < size; i++) {
            ids[i] = "e" + i;
            index.add(new AoiEntity(ids[i], center, 0, null));
        }
        for (int i = 0; i < observers; i++) {
            observerIds[i] = "o" + i;
            index.observe(observerIds[i], center, 64);
        }
    }
    /** @param sink 消费不可变结果；单线程完成一轮更新和全部观察。 */
    @Benchmark public void round(final Blackhole sink) {
        version++;
        for (int i = 0; i < size * updates / 100; i++) {
            index.update(new AoiEntity(ids[i], new Position((int) (version % 96), 0), version, null));
        }
        for (String observer : observerIds) sink.consume(index.observe(observer, center, 64));
    }
    /** @return 新观察者首批事件；每次回收全部观察者状态。 */
    @Benchmark public Object churn() {
        index.forgetObserver("temporary");
        return index.observe("temporary", center, 64);
    }
}
