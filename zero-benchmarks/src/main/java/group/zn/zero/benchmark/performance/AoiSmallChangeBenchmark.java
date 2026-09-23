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

/** 精确少量变化；enterLeave 每轮交替 ENTER/LEAVE，含完整更新成本。 @author zn */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class AoiSmallChangeBenchmark {
    /** 每个观察者的变化数。 */
    @Param({"1", "2", "100"}) public int changes;
    /** 变化类型。 */
    @Param({"update", "enterLeave", "mixed"}) public String type;
    /** 观察者数。 */
    @Param({"1", "16"}) public int observers;
    /** 单所有者索引。 */
    private InMemoryAoiIndex index;
    /** 可见位置。 */
    private final Position inside = new Position(0, 0);
    /** 不可见位置。 */
    private final Position outside = new Position(1024, 0);
    /** 实体 ID。 */
    private String[] ids;
    /** 观察者 ID。 */
    private String[] observerIds;
    /** 状态版本。 */
    private long version;
    /** 初始化 100 个可见实体与所有观察者快照。 */
    @Setup public void setup() {
        index = new InMemoryAoiIndex();
        ids = new String[100];
        observerIds = new String[observers];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "e" + i;
            index.add(new AoiEntity(ids[i], inside, 0, null));
        }
        for (int i = 0; i < observers; i++) {
            observerIds[i] = "o" + i;
            index.observe(observerIds[i], inside, 64);
        }
    }
    /** @param sink 消费每次独立不可变结果；不保留历史对象。 */
    @Benchmark public void round(final Blackhole sink) {
        version++;
        for (int i = 0; i < changes; i++) {
            boolean move = type.equals("enterLeave") || (type.equals("mixed") && i % 2 == 0);
            index.update(new AoiEntity(ids[i], move && version % 2 == 1 ? outside : inside, version, null));
        }
        for (String observer : observerIds) sink.consume(index.observe(observer, inside, 64));
    }
}
