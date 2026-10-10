package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** 精确连接关闭协调回归测试。 @author zn */
class PlayerConnectionCloseCoordinatorTest {

    /** 延迟到达的旧连接关闭只能解除旧会话，不能踢掉后续登录。 */
    @Test
    void lateCloseFromReplacedConnectionMustLeaveCurrentSessionBound() {
        PlayerLoginResult oldLogin = new PlayerLoginResult("account", 7L, "old-session", "trace");
        PlayerLoginResult newLogin = new PlayerLoginResult("account", 7L, "new-session", "trace");
        RecordingPlayerService playerService = new RecordingPlayerService(newLogin);
        PlayerConnectionCloseCoordinator coordinator = new PlayerConnectionCloseCoordinator(playerService);

        boolean removed = coordinator.onConnectionClosed(new PlayerLogoutRequest(
                oldLogin.accountId(), oldLogin.uid(), oldLogin.sessionId(), "close-trace"))
                .toCompletableFuture().join();

        assertFalse(removed);
        assertSame(newLogin, playerService.current);
        assertTrue(playerService.lastRequest.sessionId().equals(oldLogin.sessionId()));
    }

    /** 仅为验证关闭请求精确转发的最小服务替身。 */
    private static final class RecordingPlayerService implements PlayerService {
        private final PlayerLoginResult current;
        private PlayerLogoutRequest lastRequest;

        private RecordingPlayerService(final PlayerLoginResult current) {
            this.current = current;
        }

        @Override public CompletionStage<PlayerLoginResult> login(final PlayerLoginRequest request) {
            return CompletableFuture.completedFuture(current);
        }

        @Override public CompletionStage<PlayerProfile> loadPlayer(final PlayerLoadRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override public CompletionStage<Optional<PlayerProfile>> queryPlayer(final long uid, final String traceId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override public CompletionStage<Optional<Long>> querySession(final String accountId, final String traceId) {
            return CompletableFuture.completedFuture(Optional.of(current.uid()));
        }

        @Override public CompletionStage<Boolean> logout(final PlayerLogoutRequest request) {
            lastRequest = request;
            return CompletableFuture.completedFuture(current.sessionId().equals(request.sessionId()));
        }
    }
}
