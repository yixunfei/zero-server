package group.zn.zero.benchmark.performance;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.aoi.AoiEntity;
import group.zn.zero.aoi.InMemoryAoiIndex;
import group.zn.zero.aoi.Position;
import group.zn.zero.framesync.FrameInput;
import group.zn.zero.framesync.FrameMatchConfig;
import group.zn.zero.framesync.FrameMatchRuntime;
import group.zn.zero.framesync.InputTimingPolicy;
import group.zn.zero.framesync.MissingInputPolicy;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** 调度默认消息构造与游戏循环；仅使用实施前已有 API，可在固定基线 jar 上运行。 @author zn */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class GameLoopBenchmark {
    /** 线程私有 Actor 状态。 @author zn */
    @State(Scope.Thread)
    public static class ActorState {
        /** 直接执行器衡量框架成本。 */
        private ExecutorActorScheduler scheduler;
        /** 固定 Lane。 */
        private final LaneKey lane = LaneKey.custom("benchmark");
        /** 固定显式 ID 消息，用于隔离多态解析成本。 */
        private final ActorMessage explicit = new ActorMessage("id", lane, "trace", "payload");
        /** 初始化有序多态处理器；线程独占。 */
        @Setup public void setup() {
            scheduler = new ExecutorActorScheduler(Runnable::run);
            ActorHandler handler = ActorHandler.sync((context, message) -> { });
            for (Class<?> type : new Class<?>[] {Number.class, Iterable.class, Runnable.class,
                    java.util.Map.class, java.util.Collection.class, java.nio.Buffer.class, CharSequence.class}) {
                scheduler.register(type, handler);
            }
        }
    }
    /** AOI 状态；线程私有。 @author zn */
    @State(Scope.Thread)
    public static class AoiState {
        /** 实体规模。 */
        @Param({"100", "2000", "10000"}) public int size;
        /** 索引。 */
        private InMemoryAoiIndex index;
        /** 观察位置。 */
        private final Position center = new Position(0, 0);
        /** 版本。 */
        private long sequence;
        /** 初始化密集可见集；测量更新及排序输出。 */
        @Setup public void setup() {
            index = new InMemoryAoiIndex();
            for (int i = 0; i < size; i++) index.add(new AoiEntity("e" + i, center, 0, null));
            index.observe("observer", center, 64);
        }
    }
    /** 帧同步状态；线程私有。 @author zn */
    @State(Scope.Thread)
    public static class FrameState {
        /** 参与者数。 */
        @Param({"2", "8", "64"}) public int participants;
        /** 对局。 */
        private FrameMatchRuntime runtime;
        /** 初始化 REPEAT_LAST 稳定输入；每 tick 导出独立快照。 */
        @Setup public void setup() {
            runtime = new FrameMatchRuntime("match", new FrameMatchConfig(InputTimingPolicy.REJECT,
                    MissingInputPolicy.REPEAT_LAST, 4096, 4096), (frame, batch) -> { }, event -> { }, event -> { });
            for (int i = 0; i < participants; i++) {
                runtime.submit(new FrameInput("u" + i, 1, 1, 0, new byte[32], "trace")).toCompletableFuture().join();
            }
        }
    }
    /** @param state 私有状态。 @return 完成信号；修改调度计数。 */
    @Benchmark public Object actorExplicit(final ActorState state) {
        return state.scheduler.dispatch(state.explicit).toCompletableFuture().join();
    }
    /** @param state 私有状态。 @return 新建消息并完成投递；修改调度计数。 */
    @Benchmark public Object actorDefault(final ActorState state) {
        return state.scheduler.dispatch(new ActorMessage(state.lane, "payload")).toCompletableFuture().join();
    }
    /** @param state 私有状态。 @return 不可变增量事件；修改实体和观察快照。 */
    @Benchmark public Object aoiMoveObserve(final AoiState state) {
        state.index.update(new AoiEntity("e0", new Position((int) (++state.sequence % 32), 0), state.sequence, null));
        return state.index.observe("observer", state.center, 64);
    }
    /** @param state 私有状态。 @return 无变化事件列表；只维护观察状态。 */
    @Benchmark public Object aoiUnchanged(final AoiState state) {
        return state.index.observe("observer", state.center, 64);
    }
    /** @param state 私有状态。 @return 已完成 tick；推进对局。 */
    @Benchmark public Object frameTick(final FrameState state) { return state.runtime.tick().toCompletableFuture().join(); }
}
