package group.zn.zero.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 多场景并发、同场景顺序、查询快照与空场景回收验证。 @author zn */
class SceneLaneOwnershipTest {
    @Test void independentLanesPreservePrivateStateAndSnapshots() throws Exception {
        var workers = Executors.newFixedThreadPool(8);
        var scheduler = new ExecutorActorScheduler(workers);
        try (var scenes = new LocalSceneService(scheduler)) {
            List<CompletableFuture<?>> pending = new ArrayList<>();
            for (int step = 0; step < 100; step++) {
                for (int scene = 0; scene < 32; scene++) pending.add(scenes.moveWithResult(new SceneMoveRequest(
                        1, "s" + scene, new ScenePosition(step, scene), "trace")).toCompletableFuture());
            }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            for (int scene = 0; scene < 32; scene++) {
                String id = "s" + scene;
                List<SceneEntityState> snapshot = scenes.listEntities(id, "trace").toCompletableFuture().join();
                assertEquals(new ScenePosition(99, scene), snapshot.getFirst().position());
                assertThrows(UnsupportedOperationException.class, snapshot::clear);
                scenes.leaveScene(new SceneLeaveRequest(1, id, "trace")).toCompletableFuture().join();
                assertEquals(List.of(), scenes.listEntities(id, "trace").toCompletableFuture().join());
                scenes.enterScene(new SceneEnterRequest(1, id, "trace")).toCompletableFuture().join();
                assertEquals(new ScenePosition(99, scene), snapshot.getFirst().position());
            }
        } finally { scheduler.close(); workers.shutdownNow(); }
    }
}
