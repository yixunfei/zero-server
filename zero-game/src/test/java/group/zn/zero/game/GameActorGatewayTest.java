package group.zn.zero.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.remote.ActorAddress;
import group.zn.zero.actor.remote.ActorRoute;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 游戏 Actor 投递网关测试。
 *
 * @author zn
 */
class GameActorGatewayTest {

    /**
     * 验证默认构造仍走本地 Actor 调度器。
     *
     * @throws Exception 当等待 Actor 处理完成失败时抛出。
     */
    @Test
    void defaultGatewayShouldDispatchToLocalScheduler() throws Exception {
        LocalActorScheduler scheduler = new LocalActorScheduler();
        AtomicReference<String> handledTraceId = new AtomicReference<>();
        scheduler.register(TestCommand.class, ActorHandler.sync((context, message) -> {
            handledTraceId.set(context.traceId());
        }));
        GameActorGateway gateway = new GameActorGateway(scheduler);

        gateway.dispatch(GameRequestContext.client("trace-local"), LaneKey.player("1001"), new TestCommand("local"))
                .toCompletableFuture()
                .get();

        assertEquals("trace-local", handledTraceId.get());
    }

    /**
     * 验证远程路由会进入远程 Actor gateway。
     *
     * @throws Exception 当等待远程投递完成失败时抛出。
     */
    @Test
    void remoteRouteShouldDispatchToRemoteGateway() throws Exception {
        LocalActorScheduler scheduler = new LocalActorScheduler();
        LaneKey laneKey = LaneKey.scene("scene-1");
        ActorRoute route = ActorRoute.remote(
                ActorAddress.remote(laneKey, "scene-actor", 1, "scene-node-1", "zone-a"),
                "dispatchActorMessage",
                "memory",
                "actor-topic",
                "actor-group",
                laneKey.value(),
                Map.of());
        AtomicReference<ActorMessage> remoteMessage = new AtomicReference<>();
        GameActorGateway gateway = new GameActorGateway(
                scheduler,
                ignored -> route,
                (message, ignoredRoute, ignoredOptions) -> {
                    remoteMessage.set(message);
                    return CompletableFuture.completedFuture(null);
                });
        TestCommand command = new TestCommand("remote");

        gateway.dispatch(GameRequestContext.client("trace-remote"), laneKey, command)
                .toCompletableFuture()
                .get();

        assertEquals(laneKey, remoteMessage.get().laneKey());
        assertEquals("trace-remote", remoteMessage.get().traceId());
        assertSame(command, remoteMessage.get().payload());
    }

    /**
     * 测试命令。
     *
     * @author zn
     */
    private record TestCommand(String value) {
    }
}
