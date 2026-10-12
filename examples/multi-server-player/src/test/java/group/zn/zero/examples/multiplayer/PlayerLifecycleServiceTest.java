package group.zn.zero.examples.multiplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import group.zn.zero.examples.multiplayer.role.CenterServer;
import group.zn.zero.examples.multiplayer.role.LoginServer;
import group.zn.zero.examples.multiplayer.role.LogicServer;
import group.zn.zero.examples.multiplayer.rpc.AllocationRequest;
import group.zn.zero.examples.multiplayer.rpc.CreateCharacterRequest;
import group.zn.zero.examples.multiplayer.rpc.RegisterRequest;
import group.zn.zero.examples.multiplayer.rpc.RegistryRequest;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 玩家生命周期业务不变量回归；命令行 owner 与框架执行域均受明确约束。 @author zn */
class PlayerLifecycleServiceTest {
    /** Java 字符串哈希冲突不能让两个账号共享 UID 或角色。 */
    @Test
    void hashCollisionAccountsRemainIsolated() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        try (var fixture = new Fixture()) {
            AccountBinding first = fixture.players.register("Aa");
            AccountBinding second = fixture.players.register("BB");
            assertNotEquals(first.uid(), second.uid());
            var firstSession = fixture.players.login("Aa", "token-Aa", "first");
            var secondSession = fixture.players.login("BB", "token-BB", "second");
            assertEquals("first", fixture.players.createCharacter(firstSession, "first").characterName());
            assertEquals("second", fixture.players.createCharacter(secondSession, "second").characterName());
        }
    }

    /** 重复注册、重复建角通过统一返回保留明确业务错误码。 */
    @Test
    void duplicateFailuresKeepErrorCodes() {
        try (var fixture = new Fixture()) {
            LoginServer login = new LoginServer(fixture.players);
            LogicServer logic = new LogicServer(fixture.players);
            assertTrue(login.register(new RegisterRequest("alice", "r1")).success());
            assertEquals(MultiServerErrorCode.ACCOUNT_EXISTS,
                    login.register(new RegisterRequest("alice", "r2")).errorCode());
            PlayerSession session = fixture.players.login("alice", "token-alice", "l");
            assertTrue(logic.create(new CreateCharacterRequest(session, "Alice", "c1")).success());
            assertEquals(MultiServerErrorCode.CHARACTER_EXISTS,
                    logic.create(new CreateCharacterRequest(session, "Other", "c2")).errorCode());
        }
    }

    /** 重复登录及断线重连保持路由；迟到的旧断线不删除新会话。 */
    @Test
    void keepsStickyRouteAndRejectsStaleDisconnect() {
        try (var fixture = new Fixture()) {
            fixture.players.register("bob");
            PlayerSession first = fixture.players.login("bob", "token-bob", "l1");
            assertEquals(first, fixture.players.login("bob", "token-bob", "repeat"));
            PlayerSession second = fixture.players.reconnect("bob", "token-bob", "l2");
            assertNotEquals(first.sessionId(), second.sessionId());
            assertEquals(first.logicServerId(), second.logicServerId());
            assertFalse(fixture.players.disconnect(first, "late-close"));
            assertEquals(second, fixture.players.session("bob").orElseThrow());
        }
    }

    /** 认证或追踪参数失败不能先把有效会话踢下线。 */
    @Test
    void invalidReconnectPreservesCurrentSession() {
        try (var fixture = new Fixture()) {
            fixture.players.register("bob");
            PlayerSession original = fixture.players.login("bob", "token-bob", "l");
            assertThrows(MultiServerError.class, () -> fixture.players.reconnect("bob", "wrong", "retry"));
            assertThrows(MultiServerError.class, () -> fixture.players.reconnect("bob", "token-bob", " "));
            assertEquals(original, fixture.players.session("bob").orElseThrow());
        }
    }

    /** 伪造身份及失效会话不能写角色。 */
    @Test
    void rejectsForgedAndDisconnectedSessions() {
        try (var fixture = new Fixture()) {
            fixture.players.register("alice");
            PlayerSession session = fixture.players.login("alice", "token-alice", "l");
            PlayerSession forged = new PlayerSession("alice", session.uid() + 1,
                    session.sessionId(), session.logicServerId());
            assertEquals(MultiServerErrorCode.STALE_SESSION,
                    assertThrows(MultiServerError.class,
                            () -> fixture.players.createCharacter(forged, "bad")).errorCode());
            assertTrue(fixture.players.disconnect(session, "close"));
            assertThrows(MultiServerError.class, () -> fixture.players.createCharacter(session, "stale"));
            assertEquals(0, fixture.repository.count().toCompletableFuture().join());
        }
    }

    /** 真正失效缓存后回源；仓库、返回值、缓存始终使用提交后的版本。 */
    @Test
    void cacheMissLoadsCommittedRepositoryVersion() {
        try (var fixture = new Fixture()) {
            fixture.players.register("cache");
            PlayerSession session = fixture.players.login("cache", "token-cache", "l");
            PlayerCharacter created = fixture.players.createCharacter(session, "Cache");
            assertEquals(1, created.version());
            assertEquals(created, fixture.cache.get(session.uid()).toCompletableFuture().join().orElseThrow());
            fixture.cache.invalidate(session.uid()).toCompletableFuture().join();
            assertTrue(fixture.cache.get(session.uid()).toCompletableFuture().join().isEmpty());
            assertEquals(created, fixture.players.loadCharacter(session.uid()).orElseThrow());
            assertEquals(created, fixture.cache.get(session.uid()).toCompletableFuture().join().orElseThrow());
            assertEquals(created, fixture.repository.findById(session.uid()).toCompletableFuture().join().orElseThrow());
        }
    }

    /** 注册与分配共享唯一来源，既有 UID 的路由不受新注册影响。 */
    @Test
    void registeredInstancesAreActuallyRoutable() {
        CenterRouter router = new CenterRouter(List.of());
        CenterServer center = new CenterServer(router);
        assertEquals(MultiServerErrorCode.NO_LOGIC_SERVER,
                center.allocate(new AllocationRequest(1, "before")).errorCode());
        assertEquals(1, center.register(new RegistryRequest("logic-1", "localhost", 19090, "r")).orThrow().instanceCount());
        assertEquals("logic-1", center.allocate(new AllocationRequest(1, "a")).orThrow().logicServerId());
        assertEquals(2, center.register(new RegistryRequest("logic-2", "localhost", 19091, "r2")).orThrow().instanceCount());
        assertEquals(2, center.register(new RegistryRequest("logic-2", "localhost", 19091, "r3")).orThrow().instanceCount());
        assertEquals("logic-1", router.allocate(1));
        assertEquals("logic-1", router.allocate(2));
        assertEquals("logic-2", router.allocate(3));
    }

    /** 跨线程操作在状态修改前明确拒绝，受管执行器由测试关闭。 */
    @Test
    void rejectsWrongThreadBeforeMutation() {
        try (var fixture = new Fixture(); var executors = ZeroRuntimeExecutors.remoteIo("example-test")) {
            MultiServerError failure = CompletableFuture.supplyAsync(
                    () -> assertThrows(MultiServerError.class, () -> fixture.players.register("wrong")),
                    executors.remoteIoExecutor()).join();
            assertEquals(MultiServerErrorCode.WRONG_THREAD, failure.errorCode());
            assertTrue(fixture.players.account("wrong").isEmpty());
        }
    }

    /** 契约对象在进入业务前拒绝无效身份、端口与追踪标识。 */
    @Test
    void validatesContractInputs() {
        assertThrows(MultiServerError.class, () -> new RegisterRequest("a", " "));
        assertThrows(MultiServerError.class, () -> new AllocationRequest(0, "trace"));
        assertThrows(MultiServerError.class, () -> new RegistryRequest("logic", "host", 65536, "trace"));
    }

    /** 每个测试拥有独立的内存数据与调度器，关闭顺序与应用组合根一致。 */
    private static final class Fixture implements AutoCloseable {
        /** 当前测试的调度器。 */
        private final LocalActorScheduler scheduler = new LocalActorScheduler();
        /** 权威角色仓库。 */
        private final InMemoryCrudRepository<Long, PlayerCharacter> repository = new InMemoryCrudRepository<>();
        /** 可由测试显式失效的缓存。 */
        private final InMemoryCacheService<Long, PlayerCharacter> cache = new InMemoryCacheService<>();
        /** 当前线程拥有的示例编排。 */
        private final PlayerLifecycleService players = new PlayerLifecycleService(
                scheduler, new CenterRouter(List.of("logic-1", "logic-2")), repository, cache);

        /** 仅测试 owner 线程调用，先关闭玩家注册再关闭调度器。 */
        @Override
        public void close() {
            try {
                players.close();
            } finally {
                scheduler.close();
            }
        }
    }
}
