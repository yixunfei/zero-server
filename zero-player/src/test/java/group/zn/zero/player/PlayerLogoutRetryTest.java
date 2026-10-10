package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ActorSchedulerConfig;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 真实全局背压恢复后，已解绑会话仍能重试清理；不修改调度器内部状态。 @author zn */
class PlayerLogoutRetryTest {
    @Test
    void retryAfterGlobalBudgetRecoversRemovesOfflineProfile() {
        try (var scheduler = new LocalActorScheduler(new ActorSchedulerConfig(2, 2, 64));
                var service = new LocalPlayerService(scheduler, request -> 7, MissingPlayerPolicy.CREATE_DEFAULT)) {
            var login = service.login(new PlayerLoginRequest("account", "token", "login")).toCompletableFuture().join();
            service.loadPlayer(new PlayerLoadRequest(7, "load")).toCompletableFuture().join();
            var release = new CompletableFuture<Void>();
            scheduler.register(Hold.class, (context, message) -> release);
            var held = scheduler.dispatch(new ActorMessage(LaneKey.custom("busy"), new Hold()));
            var request = new PlayerLogoutRequest("account", 7, login.sessionId(), "logout");
            assertThrows(CompletionException.class, () -> service.logout(request).toCompletableFuture().join());
            release.complete(null);
            held.toCompletableFuture().join();
            assertTrue(service.querySession("account", "query").toCompletableFuture().join().isEmpty());
            assertTrue(service.logout(request).toCompletableFuture().join());
            assertTrue(service.queryPlayer(7, "query").toCompletableFuture().join().isEmpty());
            assertFalse(service.logout(request).toCompletableFuture().join());
        }
    }

    /** 无关 lane 的异步在途任务。 */
    private record Hold() { }
}
