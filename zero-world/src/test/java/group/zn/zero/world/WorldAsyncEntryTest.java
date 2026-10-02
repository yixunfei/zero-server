package group.zn.zero.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 异步入口从同一执行器和同一 lane 发起时不能同步自等待。 @author zn */
class WorldAsyncEntryTest {
    /** 当前消息完成后，排队的 enter/move 能继续在唯一 worker 上执行。 */
    @Test void sameLaneEntryReturnsWithoutWaiting() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor();
                var scheduler = new ExecutorActorScheduler(executor)) {
            var service = new LocalWorldService(scheduler);
            service.createWorld("w", "a");
            var result = new CompletableFuture<Entity>();
            scheduler.register(String.class, ActorHandler.sync((context, message) ->
                    service.enterWorldAsync("e", "w")
                            .thenCompose(entity -> service.moveEntityAsync("e", "a", 3, 4))
                            .whenComplete((entity, failure) -> {
                                if (failure != null) result.completeExceptionally(failure);
                                else result.complete(entity);
                            })));
            scheduler.dispatch(new ActorMessage(LaneKey.entity("e"), "begin"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(3, result.get(2, TimeUnit.SECONDS).x());
        }
    }
}
