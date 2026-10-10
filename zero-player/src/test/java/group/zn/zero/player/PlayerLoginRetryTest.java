package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 登录响应丢失后的重试与重新认证边界。 @author zn */
class PlayerLoginRetryTest {
    @Test void retryRecoversIdentityButNeverBypassesAuthentication() {
        AtomicInteger authentications = new AtomicInteger();
        AtomicBoolean allowed = new AtomicBoolean(true);
        try (var scheduler = new LocalActorScheduler();
                var service = new LocalPlayerService(scheduler, request -> {
                    authentications.incrementAndGet();
                    if (!allowed.get()) throw new IllegalArgumentException("revoked");
                    return 7;
                }, MissingPlayerPolicy.CREATE_DEFAULT)) {
            var first = service.login(new PlayerLoginRequest("account", "token", "first")).toCompletableFuture().join();
            var retry = service.login(new PlayerLoginRequest("account", "token", "retry")).toCompletableFuture().join();
            assertEquals(first.sessionId(), retry.sessionId());
            assertEquals("retry", retry.traceId());
            assertEquals(2, authentications.get());
            assertThrows(CompletionException.class, () -> service.login(
                    new PlayerLoginRequest("account", "different-token", "other")).toCompletableFuture().join());
            allowed.set(false);
            assertThrows(CompletionException.class, () -> service.login(
                    new PlayerLoginRequest("account", "token", "revoked")).toCompletableFuture().join());
            assertTrue(service.logout(new PlayerLogoutRequest("account", 7, retry.sessionId(), "logout"))
                    .toCompletableFuture().join());
            allowed.set(true);
            var next = service.login(new PlayerLoginRequest("account", "token", "new")).toCompletableFuture().join();
            assertNotEquals(first.sessionId(), next.sessionId());
        }
    }
}
