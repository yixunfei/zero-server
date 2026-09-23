package group.zn.zero.benchmark.performance;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.actor.scheduler.ActorSchedulerConfig;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.ThreadParams;

/** 真实生产/消费和异步占用；返回完整完成结果，不把提交速度当吞吐。 @author zn */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ActorCompletionBenchmark {
    /** Lane 规模。 */
    @Param({"1", "64"}) public int lanes;
    /** 完成方式，rejected 以未完成 handler 占满同 Lane 许可。 */
    @Param({"sync", "async", "rejected"}) public String completion;
    /** 共享调度器。 */
    private ExecutorActorScheduler scheduler;
    /** 基准独占工作池。 */
    private ExecutorService workers;
    /** 消息输入，不在计时期间创建字符串。 */
    private ActorMessage[] messages;
    /** 拒绝负载占位阶段。 */
    private final CompletableFuture<Void> gate = new CompletableFuture<>();
    /** 初始化有界共享调度器；测试专用资源在 teardown 回收。 */
    @Setup public void setup() {
        workers = Executors.newFixedThreadPool(4);
        scheduler = new ExecutorActorScheduler(workers, new ActorSchedulerConfig(
                completion.equals("rejected") ? 1 : 4096, 65536, 64));
        ActorHandler handler = switch (completion) {
            case "sync" -> ActorHandler.sync((context, message) -> { });
            case "async" -> (context, message) -> CompletableFuture.runAsync(() -> { }, workers);
            case "rejected" -> (context, message) -> gate;
            default -> throw new IllegalArgumentException(completion);
        };
        scheduler.register(String.class, handler);
        messages = new ActorMessage[lanes];
        for (int i = 0; i < lanes; i++) {
            messages[i] = new ActorMessage("id", LaneKey.custom("lane" + i), "trace", "payload");
            if (completion.equals("rejected")) scheduler.dispatch(messages[i]);
        }
    }
    /** @param thread 生产者编号。 @return 完成或明确拒绝的结果。 */
    @Benchmark public Object dispatch(final ThreadParams thread) {
        try { return scheduler.dispatch(messages[thread.getThreadIndex() % lanes]).toCompletableFuture().join(); }
        catch (java.util.concurrent.CompletionException failure) { return failure; }
    }
    /** 完成占位任务、关闭调度准入并归还线程。 */
    @TearDown public void close() { gate.complete(null); scheduler.close(); workers.close(); }
}
