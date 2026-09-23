package group.zn.zero.benchmark.performance;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.event.bus.InMemoryEventBus;
import group.zn.zero.event.handler.EventHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/** 嵌套 publish、Actor 返回发布结果及不可消除的 Future 转换成本。 @author zn */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EventCompositionBenchmark {
    /** 外层共享总线。 */
    private InMemoryEventBus outer;
    /** 同步业务副作用，防止消费逻辑被消除。 */
    private final AtomicLong handled = new AtomicLong();
    /** 组合路径中的 Actor。 */
    private ExecutorActorScheduler actor;
    /** 固定事件。 */
    private final BasicZeroEvent event = new BasicZeroEvent("id", EventType.INTERNAL, "trace");
    /** 显式身份隔离 Actor ID 变化。 */
    private final ActorMessage message = new ActorMessage("id", LaneKey.custom("event"), "trace", "payload");
    /** 初始化嵌套总线与同步 Actor 完整派发。 */
    @Setup public void setup() {
        var inner = new InMemoryEventBus(letter -> { });
        inner.register(EventType.INTERNAL, EventHandler.sync(ignored -> handled.incrementAndGet()));
        outer = new InMemoryEventBus(letter -> { });
        outer.register(EventType.INTERNAL, inner::publish);
        actor = new ExecutorActorScheduler(Runnable::run);
        actor.register(String.class, (context, request) -> outer.publish(event));
    }
    /** @return 完整嵌套发布完成结果。 */
    @Benchmark public Object nested() { return outer.publish(event).toCompletableFuture().join(); }
    /** @return 逃逸的独立 Future，明确不能由 JIT 消除的转换分配。 */
    @Benchmark public Object converted() { return outer.publish(event).toCompletableFuture(); }
    /** @return 有实际副作用的回调结果。 */
    @Benchmark public Object callback() { return outer.publish(event).thenApply(ignored -> handled.incrementAndGet()).toCompletableFuture().join(); }
    /** @return Actor 完整完成结果，计入 MinimalStage 到普通 Future 的转换。 */
    @Benchmark public Object actor() { return actor.dispatch(message).toCompletableFuture().join(); }
    /** 验证无残余准入并关闭。 */
    @TearDown public void close() {
        if (actor.statistics().pending() != 0) throw new IllegalStateException("pending actor publication");
        actor.close();
    }
}
