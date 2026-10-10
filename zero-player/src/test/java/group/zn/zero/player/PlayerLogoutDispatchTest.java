package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** SPI 拒绝、在途合并及观察者取消的端到端回归。 @author zn */
class PlayerLogoutDispatchTest {
    @Test void synchronousRejectionCanBeRetried() { rejectionRetry(true); }
    @Test void asynchronousRejectionCanBeRetried() { rejectionRetry(false); }

    private void rejectionRetry(final boolean synchronous) {
        try (var delegate = new LocalActorScheduler()) {
            var scheduler = new CleanupGate(delegate);
            scheduler.synchronous = synchronous;
            try (var service = new LocalPlayerService(scheduler, request -> 7, MissingPlayerPolicy.CREATE_DEFAULT)) {
                var login = service.login(new PlayerLoginRequest("a", "token", "login")).toCompletableFuture().join();
                service.loadPlayer(new PlayerLoadRequest(7, "load")).toCompletableFuture().join();
                var request = new PlayerLogoutRequest("a", 7, login.sessionId(), "logout");
                var first = service.logout(request).toCompletableFuture();
                service.querySession("a", "barrier").toCompletableFuture().join();
                if (!synchronous) scheduler.completion.completeExceptionally(new IllegalStateException("SPI rejected"));
                assertThrows(CompletionException.class, first::join);
                assertTrue(service.queryPlayer(7, "query").toCompletableFuture().join().isPresent());
                scheduler.passthrough = true;
                assertTrue(service.logout(request).toCompletableFuture().join());
                assertTrue(service.queryPlayer(7, "query").toCompletableFuture().join().isEmpty());
                assertFalse(service.logout(request).toCompletableFuture().join());
            }
        }
    }

    @Test void duplicateObserversShareCleanupAndCancellationIsIsolated() {
        try (var delegate = new LocalActorScheduler()) {
            var scheduler = new CleanupGate(delegate);
            try (var service = new LocalPlayerService(scheduler, request -> 7, MissingPlayerPolicy.CREATE_DEFAULT)) {
                var login = service.login(new PlayerLoginRequest("a", "token", "login")).toCompletableFuture().join();
                service.loadPlayer(new PlayerLoadRequest(7, "load")).toCompletableFuture().join();
                var request = new PlayerLogoutRequest("a", 7, login.sessionId(), "logout");
                var first = service.logout(request).toCompletableFuture();
                var second = service.logout(request).toCompletableFuture();
                service.querySession("a", "barrier").toCompletableFuture().join();
                assertEquals(1, scheduler.attempts);
                first.cancel(false);
                delegate.dispatch(scheduler.held).toCompletableFuture().join();
                scheduler.completion.complete(null);
                assertTrue(second.join());
                assertTrue(service.queryPlayer(7, "query").toCompletableFuture().join().isEmpty());
            }
        }
    }

    /** 清理阶段的可控 SPI；session lane 的查询作为可见性屏障。 */
    private static final class CleanupGate implements ActorScheduler {
        /** 真实执行器。 */
        private final ActorScheduler delegate;
        /** 清理投递结果。 */
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        /** 挂起消息。 */
        private ActorMessage held;
        /** 是否同步拒绝。 */
        private boolean synchronous;
        /** 后续是否直接执行。 */
        private volatile boolean passthrough;
        /** 物理尝试次数。 */
        private int attempts;

        private CleanupGate(final ActorScheduler delegate) { this.delegate = delegate; }

        /** 原样注册测试服务 handler。 */
        @Override public ActorSubscription register(final Class<?> type, final ActorHandler handler) {
            return delegate.register(type, handler);
        }

        /** 在清理边界模拟同步或异步拒绝。 */
        @Override public CompletionStage<Void> dispatch(final ActorMessage message) {
            if (!passthrough && message.payload().getClass().getSimpleName().equals("RemovePlayerIfOfflineCommand")) {
                attempts++;
                if (synchronous) throw new IllegalStateException("SPI rejected");
                held = message;
                return completion;
            }
            return delegate.dispatch(message);
        }
    }
}
