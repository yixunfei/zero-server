package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** 会话解绑与 player lane 清理之间的确定性交错回归。 @author zn */
class PlayerLogoutHandoffTest {
    /** 替换登录不应改变先前精确解绑的成功结果，也不应被旧清理移除。 */
    @Test void replacementBeforeCleanupPreservesSuccessfulLogout() {
        try (var delegate = new LocalActorScheduler()) {
            var scheduler = new DelayedCleanup(delegate);
            try (var service = new LocalPlayerService(scheduler, request -> 7, MissingPlayerPolicy.CREATE_DEFAULT)) {
                var first = service.login(new PlayerLoginRequest("account", "token", "first")).toCompletableFuture().join();
                var logout = service.logout(new PlayerLogoutRequest("account", 7, first.sessionId(), "logout"));
                assertFalse(logout.toCompletableFuture().isDone());
                assertTrue(service.querySession("account", "query").toCompletableFuture().join().isEmpty());
                var replacement = service.login(new PlayerLoginRequest("account", "token", "next")).toCompletableFuture().join();
                service.loadPlayer(new PlayerLoadRequest(7, "load")).toCompletableFuture().join();
                delegate.dispatch(scheduler.held).toCompletableFuture().join();
                scheduler.completion.complete(null);
                assertTrue(logout.toCompletableFuture().join());
                assertTrue(service.querySession("account", "query").toCompletableFuture().join().isPresent());
                assertTrue(service.queryPlayer(7, "query").toCompletableFuture().join().isPresent());
                assertFalse(service.logout(new PlayerLogoutRequest("account", 7, first.sessionId(), "stale"))
                        .toCompletableFuture().join());
                assertFalse(first.sessionId().equals(replacement.sessionId()));
            }
        }
    }

    /** 只挂起跨 lane 清理消息，其他调用保持真实本地调度行为。 */
    private static final class DelayedCleanup implements ActorScheduler {
        /** 实际执行器。 */
        private final ActorScheduler delegate;
        /** 被挂起的清理消息。 */
        private ActorMessage held;
        /** 被挂起调度的结果。 */
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private DelayedCleanup(final ActorScheduler delegate) { this.delegate = delegate; }

        @Override public ActorSubscription register(final Class<?> type, final ActorHandler handler) {
            return delegate.register(type, handler);
        }

        @Override public CompletionStage<Void> dispatch(final ActorMessage message) {
            if (message.payload().getClass().getSimpleName().equals("RemovePlayerIfOfflineCommand")) {
                held = message;
                return completion;
            }
            return delegate.dispatch(message);
        }
    }
}
