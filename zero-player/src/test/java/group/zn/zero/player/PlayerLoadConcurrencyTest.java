package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** 玩家异步快照必须回到正确 lane 且不能倒退版本。 @author zn */
class PlayerLoadConcurrencyTest {
    /** 同一账号第二次登录被拒绝，不覆盖已建立的会话。 */
    @Test void duplicateLoginShouldBeRejectedWithoutOverwritingSession() {
        try (var service = service(new InMemoryCacheService<>())) {
            service.login(new PlayerLoginRequest("account", "token-1", "trace-1"))
                    .toCompletableFuture().join();
            var duplicate = service.login(new PlayerLoginRequest("account", "token-2", "trace-2"));
            var failure = assertThrows(CompletionException.class,
                    () -> duplicate.toCompletableFuture().join());
            assertEquals(PlayerErrorCode.ACCOUNT_ALREADY_ONLINE,
                    ((group.zn.zero.core.error.ZeroException) failure.getCause()).errorCode());
            assertEquals(1L, service.querySession("account", "q").toCompletableFuture().join().orElseThrow());
        }
    }
    /** 先返回的新版本不被较早请求的迟到旧版本覆盖。 */
    @Test void delayedOldSnapshotDoesNotReplaceNewerProfile() {
        var cache = new ControlledCache();
        var old = cache.next();
        var fresh = cache.next();
        try (var service = service(cache)) {
            var first = service.loadPlayer(new PlayerLoadRequest(1, "t"));
            var second = service.loadPlayer(new PlayerLoadRequest(1, "t"));
            fresh.complete(Optional.of(new PlayerProfile(1, 2, "new", true)));
            second.toCompletableFuture().join();
            old.complete(Optional.of(new PlayerProfile(1, 1, "old", true)));
            assertEquals(2, first.toCompletableFuture().join().version());
            assertEquals(2, service.queryPlayer(1, "t").toCompletableFuture().join().orElseThrow().version());
        }
    }
    /** 关闭撤销 handler 后的读取完成必须结束返回结果，错误 ID 不得写入其他 lane。 */
    @Test void closeAndMismatchedIdentityCompleteExceptionally() {
        for (boolean close : new boolean[]{true, false}) {
            var cache = new ControlledCache();
            var pending = cache.next();
            try (var service = service(cache)) {
                var load = service.loadPlayer(new PlayerLoadRequest(1, "t")).toCompletableFuture();
                if (close) service.close();
                pending.complete(Optional.of(new PlayerProfile(close ? 1 : 2, "p", true)));
                assertTrue(load.isDone());
                assertThrows(CompletionException.class, load::join);
            }
        }
    }
    private static LocalPlayerService service(
            group.zn.zero.cache.CacheService<Long, PlayerProfile> cache) {
        var repository = new InMemoryCrudRepository<Long, PlayerProfile>();
        repository.save(new PlayerProfile(1, "registered", true)).toCompletableFuture().join();
        return new LocalPlayerService(new LocalActorScheduler(), account -> 1L, repository, cache);
    }
    /** 依序提供受控读取结果。 */
    private static final class ControlledCache extends InMemoryCacheService<Long, PlayerProfile> {
        /** 仅读取入口消费的测试队列。 */
        private final ArrayDeque<CompletableFuture<Optional<PlayerProfile>>> reads = new ArrayDeque<>();
        private CompletableFuture<Optional<PlayerProfile>> next() {
            var result = new CompletableFuture<Optional<PlayerProfile>>(); reads.add(result); return result;
        }
        /** @return 下一次受控读取；仅测试线程调用。 */
        @Override public CompletionStage<Optional<PlayerProfile>> get(Long key) { return reads.remove(); }
    }
}
