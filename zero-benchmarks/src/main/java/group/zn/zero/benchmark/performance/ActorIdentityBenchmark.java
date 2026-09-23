package group.zn.zero.benchmark.performance;

import group.zn.zero.actor.ActorContext;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** 在计时区内创建真实消息和上下文；多线程共享生产 ID 序号。 @author zn */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ActorIdentityBenchmark {
    /** 身份来源。 */
    @Param({"default", "trace", "explicit"}) public String identity;
    /** 稳定 Lane。 */
    private final LaneKey lane = LaneKey.custom("identity");
    /** @param sink 同时消费消息与上下文，允许正常 JIT 优化。 */
    @Benchmark public void construct(final Blackhole sink) {
        ActorMessage message = switch (identity) {
            case "default" -> new ActorMessage(lane, "payload");
            case "trace" -> new ActorMessage(lane, "upstream", "payload");
            case "explicit" -> new ActorMessage("id", lane, "upstream", "payload");
            default -> throw new IllegalArgumentException(identity);
        };
        sink.consume(message);
        sink.consume(ActorContext.from(message));
    }
}
