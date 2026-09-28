package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import group.zn.zero.data.repository.CrudRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 缺失玩家策略与登录仓库校验回归。 @author zn */
class MissingPlayerPolicyTest {
    /** 两次读取都未命中时，只创建一次；后到的条件写冲突读取获胜记录。 */
    @Test void concurrentCreationKeepsWinningProfile() {
        var backing = new InMemoryCrudRepository<Long, PlayerProfile>();
        var firstRead = new CompletableFuture<Optional<PlayerProfile>>();
        var secondRead = new CompletableFuture<Optional<PlayerProfile>>();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        @SuppressWarnings("unchecked")
        CrudRepository<Long, PlayerProfile> repository = (CrudRepository<Long, PlayerProfile>) Proxy.newProxyInstance(
                CrudRepository.class.getClassLoader(), new Class<?>[]{CrudRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        int read = reads.incrementAndGet();
                        if (read == 1) return firstRead;
                        if (read == 2) return secondRead;
                    }
                    try { return method.invoke(backing, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        try (var service = new LocalPlayerService(new LocalActorScheduler(), request -> 7, repository,
                new InMemoryCacheService<>(), MissingPlayerPolicy.CREATE_DEFAULT)) {
            var first = service.loadPlayer(new PlayerLoadRequest(7, "first"));
            var second = service.loadPlayer(new PlayerLoadRequest(7, "second"));
            firstRead.complete(Optional.empty());
            secondRead.complete(Optional.empty());
            assertEquals(1, first.toCompletableFuture().join().version());
            assertEquals(first.toCompletableFuture().join(), second.toCompletableFuture().join());
            assertEquals(1, backing.findById(7L).toCompletableFuture().join().orElseThrow().version());
        }
    }
    /** 默认拒绝未知 UID，即使缓存存在也不能建立登录会话。 */
    @Test void defaultsRejectWithoutCreatingProfileOrSession() {
        var repository = new InMemoryCrudRepository<Long, PlayerProfile>();
        var cache = new InMemoryCacheService<Long, PlayerProfile>();
        try (var service = new LocalPlayerService(new LocalActorScheduler(), request -> 7, repository, cache)) {
            assertMissing(() -> service.loadPlayer(new PlayerLoadRequest(7, "trace")).toCompletableFuture().join());
            cache.put(7L, new PlayerProfile(7, "stale-cache", true)).toCompletableFuture().join();
            assertMissing(() -> service.login(new PlayerLoginRequest("account", "token", "trace"))
                    .toCompletableFuture().join());
            assertTrue(repository.findById(7L).toCompletableFuture().join().isEmpty());
            assertTrue(service.querySession("account", "trace").toCompletableFuture().join().isEmpty());
        }
    }

    /** 显式原型策略支持直接加载与首次登录建档，重复加载不覆盖已有记录。 */
    @Test void explicitCreationSupportsLoginAndLoad() {
        var repository = new InMemoryCrudRepository<Long, PlayerProfile>();
        try (var service = new LocalPlayerService(new LocalActorScheduler(), request -> 7, repository,
                new InMemoryCacheService<>(), MissingPlayerPolicy.CREATE_DEFAULT)) {
            assertEquals(7, service.login(new PlayerLoginRequest("account", "token", "trace"))
                    .toCompletableFuture().join().uid());
            var loaded = service.loadPlayer(new PlayerLoadRequest(8, "trace")).toCompletableFuture().join();
            assertEquals(1, loaded.version());
            assertEquals(loaded, service.loadPlayer(new PlayerLoadRequest(8, "trace")).toCompletableFuture().join());
            assertTrue(repository.findById(7L).toCompletableFuture().join().isPresent());
        }
    }

    /** 默认严格模式接受已经注册的玩家。 */
    @Test void registeredPlayerCanLoginAndLoad() {
        var repository = new InMemoryCrudRepository<Long, PlayerProfile>();
        repository.save(new PlayerProfile(7, "registered", true)).toCompletableFuture().join();
        try (var service = new LocalPlayerService(new LocalActorScheduler(), request -> 7, repository,
                new InMemoryCacheService<>())) {
            assertEquals(7, service.login(new PlayerLoginRequest("account", "token", "trace"))
                    .toCompletableFuture().join().uid());
            assertEquals(1, service.loadPlayer(new PlayerLoadRequest(7, "trace")).toCompletableFuture().join().version());
        }
    }

    private void assertMissing(Runnable action) {
        var failure = assertThrows(CompletionException.class, action::run);
        Throwable cause = failure;
        while (cause instanceof CompletionException) cause = cause.getCause();
        assertEquals(PlayerErrorCode.PLAYER_NOT_FOUND, ((ZeroException) cause).errorCode());
    }
}
