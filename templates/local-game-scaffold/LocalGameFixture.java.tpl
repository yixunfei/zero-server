package __PACKAGE__;

import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.player.LocalPlayerService;
import group.zn.zero.player.PlayerUidResolver;
import group.zn.zero.scene.LocalSceneService;
import java.util.Objects;

/** Composition fixture for the local business services. */
public final class LocalGameFixture implements AutoCloseable {
    private final LocalPlayerService playerService;
    private final LocalSceneService sceneService;
    private final LocalGameObservation observation;

    /** Creates services from the injected actor scheduler; no executor is created here. */
    public LocalGameFixture(final group.zn.zero.runtime.api.GameRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        ActorScheduler scheduler = new group.zn.zero.game.ContextualActorScheduler(
                runtime.require(group.zn.zero.runtime.actor.ActorRuntime.ACTOR_SCHEDULER));
        this.observation = new LocalGameObservation();
        PlayerUidResolver resolver = request -> 1001L;
        var repository = runtime.require(group.zn.zero.runtime.data.DataRuntime.REPOSITORIES).create(
                new group.zn.zero.data.repository.RepositoryRequest<>("players",
                        group.zn.zero.player.PlayerProfileRepository.DEFINITION));
        this.playerService = new LocalPlayerService(scheduler, resolver, repository,
                new group.zn.zero.cache.InMemoryCacheService<>(), group.zn.zero.player.MissingPlayerPolicy.CREATE_DEFAULT,
                runtime.require(group.zn.zero.runtime.bootstrap.RuntimeBasics.EXECUTORS).remoteIoExecutor());
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
