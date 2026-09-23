package group.zn.zero.runtime.actor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.error.ActorErrorCode;
import group.zn.zero.actor.scheduler.ActorSchedulerConfig;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 默认组合根必须装配预算并在执行器关闭前失败通知队列。 @author zn */
class ActorRuntimeBudgetTest {
    @Test void runtimeOwnsConfiguredSchedulerAndClosesAdmission() {
        var runtime = RuntimeBasics.builder(new MapZeroConfig(Map.of()))
                .install(ActorRuntime.module(new ActorSchedulerConfig(2, 2, 1))).build();
        runtime.start();
        var scheduler = (ExecutorActorScheduler) runtime.require(ActorRuntime.ACTOR_SCHEDULER);
        var held = new CompletableFuture<Void>();
        scheduler.register(String.class, (context, message) -> held);
        ActorMessage message = new ActorMessage(LaneKey.custom("lane"), "payload");
        scheduler.dispatch(message);
        var queued = scheduler.dispatch(message).toCompletableFuture();
        assertCode(ActorErrorCode.CAPACITY_EXCEEDED, scheduler.dispatch(message).toCompletableFuture());
        runtime.close();
        assertCode(ActorErrorCode.SCHEDULER_CLOSED, queued);
        assertCode(ActorErrorCode.SCHEDULER_CLOSED, scheduler.dispatch(message).toCompletableFuture());
        held.complete(null);
        assertEquals(0, scheduler.statistics().pending());
    }
    private static void assertCode(final ActorErrorCode code, final CompletableFuture<Void> result) {
        var failure = assertThrows(CompletionException.class, result::join);
        assertEquals(code, ((ZeroException) failure.getCause()).errorCode());
    }
}
