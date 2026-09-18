package __PACKAGE__;

import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.player.LocalPlayerService;
import group.zn.zero.player.PlayerUidResolver;
import group.zn.zero.scene.LocalSceneService;
import java.util.Objects;
import java.util.function.LongFunction;

/** Composition fixture for the local business services. */
public final class LocalGameFixture implements AutoCloseable {
    private final LocalPlayerService playerService;
    private final LocalSceneService sceneService;
    private final LocalGameObservation observation;

    /** Creates services from the injected actor scheduler; no executor is created here. */
    public LocalGameFixture(final ActorScheduler scheduler, final LongFunction<String> profileLoader) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(profileLoader, "profileLoader");
        this.observation = new LocalGameObservation();
        PlayerUidResolver resolver = request -> 1001L;
        this.playerService = new LocalPlayerService(scheduler, resolver);
        this.sceneService = new LocalSceneService(scheduler);
    }

    /** Returns the player service port. */
    public LocalPlayerService playerService() { return playerService; }

    /** Returns the scene service port. */
    public LocalSceneService sceneService() { return sceneService; }

    /** Returns the observation facade. */
    public LocalGameObservation observation() { return observation; }

    /** Closes service subscriptions. */
    @Override
    public void close() {
        sceneService.close();
        playerService.close();
    }
}
