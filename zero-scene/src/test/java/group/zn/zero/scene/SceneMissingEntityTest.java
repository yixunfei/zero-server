package group.zn.zero.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.core.error.ZeroException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 验证未进入实体的领域错误及后续正常进入。 @author zn */
class SceneMissingEntityTest {
    @Test void missingEntityUsesStableCodeAndDoesNotPreventLaterEntry() {
        try (var service = new LocalSceneService(new group.zn.zero.actor.scheduler.LocalActorScheduler())) {
            var failure = assertThrows(CompletionException.class, () -> service.moveWithResult(
                    new SceneMoveRequest(1, "s", new ScenePosition(2, 3), "trace")).toCompletableFuture().join());
            assertEquals(SceneErrorCode.NOT_ENTERED, ((ZeroException) failure.getCause()).errorCode());
            service.enterScene(new SceneEnterRequest(1, "s", "trace")).toCompletableFuture().join();
            assertEquals(1, service.listEntities("s", "trace").toCompletableFuture().join().size());
        }
    }
}
