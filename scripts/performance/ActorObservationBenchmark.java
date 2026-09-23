package group.zn.zero.benchmark.performance;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.ThreadParams;

/** 七个生产者加一个连续观测者；明确统计汇总转移到读取端的成本。 @author zn */
@State(Scope.Group)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ActorObservationBenchmark {
    /** 基准执行器。 */
    private ExecutorService workers;
    /** 共享被测调度器。 */
    private ExecutorActorScheduler scheduler;
    /** 稳定消息。 */
    private ActorMessage[] messages;
    /** 初始化真实消费池。 */
    @Setup public void setup() {
        workers = Executors.newFixedThreadPool(4);
        scheduler = new ExecutorActorScheduler(workers);
        scheduler.register(String.class, ActorHandler.sync((context, message) -> { }));
        messages = new ActorMessage[64];
        for (int i = 0; i < messages.length; i++) messages[i] = new ActorMessage("id", LaneKey.custom("lane" + i), "trace", "body");
    }
    /** @return 完整处理结果；与观测共享调度器。 */
    @Benchmark @Group("observed") @GroupThreads(7)
    public Object dispatch(final ThreadParams thread) {
        return scheduler.dispatch(messages[thread.getThreadIndex() % messages.length]).toCompletableFuture().join();
    }
    /** @return 非线性一致的观测快照；包含真实读取成本。 */
    @Benchmark @Group("observed") @GroupThreads(1)
    public Object statistics() { return scheduler.statistics(); }
    /** 关闭全部基准资源。 */
    @TearDown public void close() { scheduler.close(); workers.close(); }
}
