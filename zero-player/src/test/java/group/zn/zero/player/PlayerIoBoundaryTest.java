package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 返回 CompletionStage 的后端调用本身也必须离开入口线程。 @author zn */
class PlayerIoBoundaryTest {
    /** UID 解析和 Repository 方法调用都在显式 IO 域，而非登录调用线程。 */
    @Test void loginAndLoadInvokeCustomBackendOffCallerThread() throws Exception {
        Thread caller = Thread.currentThread();
        var reads = new AtomicInteger();
        var backing = new InMemoryCrudRepository<Long, PlayerProfile>();
        backing.save(new PlayerProfile(7, "known", true)).toCompletableFuture().join();
        @SuppressWarnings("unchecked")
        CrudRepository<Long, PlayerProfile> repository = (CrudRepository<Long, PlayerProfile>) Proxy.newProxyInstance(
                CrudRepository.class.getClassLoader(), new Class<?>[] {CrudRepository.class}, (proxy, method, args) -> {
                    assertNotSame(caller, Thread.currentThread());
                    if (method.getName().equals("findById")) reads.incrementAndGet();
                    try { return method.invoke(backing, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        assertThrows(IllegalArgumentException.class, () -> new LocalPlayerService(new LocalActorScheduler(),
                request -> 7, repository, new InMemoryCacheService<>()));
        try (var executor = Executors.newSingleThreadExecutor();
                var service = new LocalPlayerService(new LocalActorScheduler(), request -> {
                    assertNotSame(caller, Thread.currentThread()); return 7;
                }, repository, new InMemoryCacheService<>(), executor)) {
            assertEquals(7, service.login(new PlayerLoginRequest("account", "token", "trace"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).uid());
            assertEquals(7, service.loadPlayer(new PlayerLoadRequest(7, "trace"))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS).uid());
            assertEquals(2, reads.get());
        }
    }
}
