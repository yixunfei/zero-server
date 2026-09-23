package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.player.LocalPlayerService;
import group.zn.zero.player.PlayerLoadRequest;
import group.zn.zero.player.PlayerLoginRequest;
import group.zn.zero.player.PlayerLoginResult;
import group.zn.zero.player.PlayerProfile;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.scene.LocalSceneService;
import group.zn.zero.scene.SceneEnterRequest;
import group.zn.zero.scene.SceneEntityState;
import group.zn.zero.scene.SceneLeaveRequest;
import group.zn.zero.scene.SceneLeaveResult;
import group.zn.zero.scene.SceneMoveRequest;
import group.zn.zero.scene.SceneMoveResult;
import group.zn.zero.scene.ScenePosition;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 阶段 3 基础业务模块直接接入测试。
 *
 * <p>本测试验证登录、玩家加载、进入场景、移动和查询已经可以通过正式模块 API 接入，
 * 不再只依赖 test scope 的单点 smoke 夹具。
 *
 * @author zn
 */
class Stage3FoundationRuntimeTest {

    /**
     * 验证玩家与场景基础 API 可以通过 starter 原型执行器直接接入。
     */
    @Test
    void foundationModulesShouldRunThroughStarterPrototypeExecutors() {
        GameRuntime components = LocalRuntime.builder(
                new MapZeroConfig(Map.of(
                        ZeroRuntimeConfigKeys.ZERO_MODE, "stage3-foundation",
                        ZeroRuntimeConfigKeys.ZERO_NAME, "stage3-foundation-runtime")),
                new InMemoryLogSink(),
                ZeroRuntimeExecutors.localPrototype("zero-stage3-foundation", 2))
                .build();
        ZeroServerApplication application = new ZeroServerApplication(components);
        InMemoryCrudRepository<Long, PlayerProfile> playerRepository = new InMemoryCrudRepository<>();
        InMemoryCacheService<Long, PlayerProfile> playerCache = new InMemoryCacheService<>();
        application.start();
        try (LocalPlayerService playerService = new LocalPlayerService(
                components.require(ActorRuntime.ACTOR_SCHEDULER),
                request -> 1001L,
                playerRepository,
                playerCache);
                LocalSceneService sceneService = new LocalSceneService(
                        components.require(ActorRuntime.ACTOR_SCHEDULER))) {
            PlayerLoginResult loginResult = playerService
                    .login(new PlayerLoginRequest("guest-1001", "token-local", "trace-login"))
                    .toCompletableFuture()
                    .join();
            PlayerProfile profile = playerService
                    .loadPlayer(new PlayerLoadRequest(loginResult.uid(), "trace-load"))
                    .toCompletableFuture()
                    .join();
            SceneEntityState entered = sceneService
                    .enterScene(new SceneEnterRequest(loginResult.uid(), "scene-1", "trace-enter"))
                    .toCompletableFuture()
                    .join();
            SceneMoveResult moveResult = sceneService
                    .moveWithResult(new SceneMoveRequest(
                            loginResult.uid(),
                            "scene-1",
                            new ScenePosition(7, 11),
                            "trace-move"))
                    .toCompletableFuture()
                    .join();
            SceneEntityState moved = moveResult.currentState();

            assertEquals(new PlayerLoginResult("guest-1001", 1001L, "trace-login"), loginResult);
            assertEquals(new PlayerProfile(1001L, 1L, "player-1001", true), profile);
            assertEquals(profile, playerRepository.findById(1001L).toCompletableFuture().join().orElseThrow());
            assertEquals(profile, playerCache.get(1001L).toCompletableFuture().join().orElseThrow());
            assertEquals(new ScenePosition(0, 0), entered.position());
            assertEquals(entered, moveResult.previousState().orElseThrow());
            assertEquals(new ScenePosition(7, 11), moved.position());
            assertEquals(1001L, playerService.querySession("guest-1001", "trace-gm")
                    .toCompletableFuture()
                    .join()
                    .orElseThrow());
            assertEquals(profile, playerService.queryPlayer(1001L, "trace-gm")
                    .toCompletableFuture()
                    .join()
                    .orElseThrow());
            assertEquals(moved, sceneService.queryEntity("scene-1", 1001L, "trace-gm")
                    .toCompletableFuture()
                    .join()
                    .orElseThrow());
            assertEquals(java.util.List.of(moved), sceneService.listEntities("scene-1", "trace-gm")
                    .toCompletableFuture()
                    .join());
            SceneLeaveResult leaveResult = sceneService
                    .leaveScene(new SceneLeaveRequest(1001L, "scene-1", "trace-leave"))
                    .toCompletableFuture()
                    .join();
            assertEquals(moved, leaveResult.leftState().orElseThrow());
            assertTrue(leaveResult.removed());
            assertTrue(sceneService.listEntities("scene-1", "trace-gm")
                    .toCompletableFuture()
                    .join()
                    .isEmpty());
            assertTrue(components.report().plan().components().stream()
                    .anyMatch(component -> component.componentId().equals(
                            StandardRuntimeCapabilityModel.LOCAL_ACTOR)));
        } finally {
            application.stop();
        }
    }
}
