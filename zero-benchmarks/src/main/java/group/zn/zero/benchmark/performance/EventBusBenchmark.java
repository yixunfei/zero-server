package group.zn.zero.benchmark.performance;

import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.event.bus.InMemoryEventBus;
import group.zn.zero.event.handler.EventHandler;
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

/** 共享总线的完成成本；异步执行器仅属于基准，包含跨线程完成。 @author zn */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class EventBusBenchmark {
    /** 处理器数量。 */
    @Param({"0", "1", "8"}) public int handlers;
    /** 完成方式。 */
    @Param({"sync", "completed", "async", "failure"}) public String completion;
    /** 调用者的实际消费方式。 */
    @Param({"join", "ignore", "then", "when"}) public String consumption;
    /** 所有发布线程共享。 */
    private InMemoryEventBus bus;
    /** 不可变事件。 */
    private final BasicZeroEvent event = new BasicZeroEvent("event", EventType.INTERNAL, "trace");
    /** 基准独占的异步完成资源。 */
    private ExecutorService executor;

    /** 初始化共享注册表；仅基准使用线程池，结束时关闭。 */
    @Setup public void setup() {
        executor = Executors.newFixedThreadPool(2);
        bus = new InMemoryEventBus(letter -> { });
        for (int i = 0; i < handlers; i++) {
            EventHandler handler = switch (completion) {
                case "sync" -> EventHandler.sync(ignored -> { });
                case "completed" -> ignored -> CompletableFuture.completedFuture(null);
                case "async" -> ignored -> CompletableFuture.runAsync(() -> { }, executor);
                case "failure" -> EventHandler.sync(ignored -> { throw new IllegalStateException("benchmark"); });
                default -> throw new IllegalArgumentException(completion);
            };
            bus.register(EventType.INTERNAL, handler);
        }
    }

    /** @return 完整派发的结果或异常；失败路径不保留死信。 */
    @Benchmark public Object publish() {
        try {
            var result = bus.publish(event);
            return switch (consumption) {
                case "ignore" -> {
                    if (completion.equals("async")) result.toCompletableFuture().join();
                    yield result;
                }
                case "then" -> result.thenRun(() -> { }).toCompletableFuture().join();
                case "when" -> result.whenComplete((value, failure) -> { }).toCompletableFuture().join();
                default -> result.toCompletableFuture().join();
            };
        }
        catch (java.util.concurrent.CompletionException failure) { return failure; }
    }

    /** 注册与注销竞争成本；可用 JMH 多线程运行，不改变已有处理器。 */
    @Benchmark public void registration() {
        bus.register(EventType.INTERNAL, EventHandler.sync(ignored -> { })).close();
    }

    /** 回收基准执行器；无在途工作。 */
    @TearDown public void close() { executor.close(); }
}
