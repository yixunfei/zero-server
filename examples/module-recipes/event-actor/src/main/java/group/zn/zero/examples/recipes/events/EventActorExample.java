package group.zn.zero.examples.recipes.events;

import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.event.EventType;
import group.zn.zero.event.ZeroEvent;
import group.zn.zero.game.GameActorGateway;
import group.zn.zero.game.GameRequestContext;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.event.EventRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionStage;

/**
 * 内部事件 → GameActorGateway → 玩家 lane → 积分状态。
 * Actor handler 只做短时内存操作，异步完成信号一路返回事件总线。
 * @author zn
 */
public final class EventActorExample {
    private EventActorExample() { }

    /**
     * 运行并打印结果；独立调用互不共享状态。
     * @param args 未使用。
     * @throws Exception 异步处理失败、超时或线程中断。
     */
    public static void main(final String[] args) throws Exception {
        System.out.println("event-actor=" + runFlow());
    }

    /**
     * 顺序发布两次加分事件；状态仅由同一玩家 lane 写入。
     * @return 非空不可变结果，trace 列表有序且不可变，可跨线程读取。
     * @throws Exception 异步处理失败、五秒内未完成或线程中断。
     */
    public static Result runFlow() throws Exception {
        var lane = LaneKey.player("player-1001");
        var wallet = new Wallet();
        try (var runtime = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeBasics.module(new MapZeroConfig(Map.of()),
                        () -> ZeroRuntimeExecutors.localPrototype("recipe-events", 2)))
                .install(EventRuntime.module()).install(ActorRuntime.module()).build()) {
            var scheduler = runtime.require(ActorRuntime.ACTOR_SCHEDULER);
            try (var actorRegistration = scheduler.register(Credit.class, ActorHandler.sync((context, message) -> {
                var credit = (Credit) message.payload();
                if (credit.points() <= 0) {
                    throw ZeroException.of(SystemErrorCode.INVALID_ARGUMENT);
                }
                wallet.points += credit.points();
                wallet.traces.add(context.traceId());
            }))) {
                var gateway = new GameActorGateway(scheduler);
                var events = runtime.require(EventRuntime.EVENT_BUS);
                try (var eventRegistration = events.register(EventType.INTERNAL, event -> {
                    if (event instanceof CreditEvent credit) {
                        return gateway.dispatch(new GameRequestContext(credit.traceId(), "internal"),
                                lane, new Credit(credit.points()));
                    }
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }, 0)) {
                    runtime.start();
                    // 仅示例主线程等待；事件回调和 Actor handler 不阻塞。
                    events.publish(new CreditEvent("credit-1", "trace-1", 10)).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    String rejection = rejectedCreditCode(events.publish(new CreditEvent("invalid", "trace-rejected", -1)));
                    events.publish(new CreditEvent("credit-2", "trace-2", 20)).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    return new Result(wallet.points, wallet.traces, rejection);
                }
            }
        }
    }

    private static String rejectedCreditCode(final CompletionStage<Void> result) throws Exception {
        try {
            result.toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure;
            while (cause instanceof ExecutionException || cause instanceof CompletionException) {
                cause = cause.getCause();
            }
            if (cause instanceof ZeroException zero && zero.errorCode() == SystemErrorCode.INVALID_ARGUMENT) {
                return zero.errorCode().code();
            }
            throw failure;
        }
        throw new IllegalStateException("非法积分必须被拒绝");
    }

    /** 仅由本次固定玩家 lane 写入，主线程在完成信号后读取。 @author zn */
    private static final class Wallet {
        /** 玩家积分；不可跨 lane 直接修改。 */
        private int points;
        /** 按处理顺序记录链路标识，仅用于本地演示。 */
        private final List<String> traces = new ArrayList<>();
    }

    /** 不可变加分命令。 @param points 本次增量。 @author zn */
    private record Credit(int points) { }

    /**
     * 不可变业务事件；本例全部归属于固定玩家。
     * @param eventId 唯一事件标识；不表示框架自动实现业务幂等。
     * @param traceId 链路标识。
     * @param points 积分增量。
     * @author zn
     */
    private record CreditEvent(String eventId, String traceId, int points) implements ZeroEvent {
        /** @return 内部事件类型；线程安全，无状态修改。 */
        @Override
        public EventType eventType() { return EventType.INTERNAL; }
    }

    /**
     * 不可变处理结果；线程安全。
     * @param points 最终积分。
     * @param traces 不可变有序 trace 列表，允许为空。
     * @param rejectionCode 非法积分的稳定错误码。
     * @author zn
     */
    public record Result(int points, List<String> traces, String rejectionCode) {
        /** 复制列表，不保留可变业务状态；传入 null 时抛出 NullPointerException。 */
        public Result { traces = List.copyOf(traces); }
    }
}
