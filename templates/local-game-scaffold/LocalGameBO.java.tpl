package __PACKAGE__;

import __PACKAGE__.generated.bo.GameEnterSceneEventBO;
import __PACKAGE__.generated.bo.GameLoginEventBO;
import __PACKAGE__.generated.bo.GameMoveEventBO;
import __PACKAGE__.generated.dto.GameEnterSceneProtocolDTO;
import __PACKAGE__.generated.dto.GameLoginProtocolDTO;
import __PACKAGE__.generated.dto.GameMoveProtocolDTO;
import group.zn.zero.player.LocalPlayerService;
import group.zn.zero.player.PlayerLoginRequest;
import group.zn.zero.player.PlayerLoginResult;
import group.zn.zero.scene.LocalSceneService;
import group.zn.zero.scene.SceneEnterRequest;
import group.zn.zero.scene.SceneMoveRequest;
import group.zn.zero.scene.SceneMoveResult;
import group.zn.zero.scene.ScenePosition;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Async business adapter for generated protocol events. It never blocks on a stage. */
public final class LocalGameBO implements GameLoginEventBO, GameEnterSceneEventBO, GameMoveEventBO {

    /**
     * Optional per-command observer used by network adapters and tests.
     */
    private final LocalPlayerService playerService;
    private final LocalSceneService sceneService;
    private final LocalGameObservation observation;
    private final CommandObserver observer;

    /**
     * Creates an adapter without an observer; async outcomes are still counted.
     *
     * @param playerService player port; never null.
     * @param sceneService scene port; never null.
     */
    public LocalGameBO(final LocalPlayerService playerService, final LocalSceneService sceneService) {
        this(playerService, sceneService, LocalGameObservation.NO_OP_OBSERVER);
    }

    /**
     * Creates an adapter from injected business ports and observation.
     *
     * @param playerService player port; never null.
     * @param sceneService scene port; never null.
     * @param observation global observation facade; never null.
     */
    public LocalGameBO(final LocalPlayerService playerService, final LocalSceneService sceneService,
            final LocalGameObservation observation) {
        this(playerService, sceneService, observation, CommandObserver.NO_OP);
    }

    /**
     * Creates an adapter from injected ports, observation and a per-command observer.
     *
     * @param playerService player port; never null.
     * @param sceneService scene port; never null.
     * @param observation global observation facade; never null.
     * @param observer observer invoked synchronously when a command is accepted; never null.
     */
    public LocalGameBO(final LocalPlayerService playerService, final LocalSceneService sceneService,
            final LocalGameObservation observation, final CommandObserver observer) {
        this.playerService = Objects.requireNonNull(playerService, "playerService");
        this.sceneService = Objects.requireNonNull(sceneService, "sceneService");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Creates an adapter for a network entry point that only needs per-command observation.
     *
     * <p>The TCP entry point calls this factory so business logging and metrics stay in
     * business code instead of the transport handler.</p>
     *
     * @param playerService player port; never null.
     * @param sceneService scene port; never null.
     * @param observer per-command observer; never null.
     * @return adapter; never null; thread-safe while the injected ports are.
     */
    public static LocalGameBO forClientAdapter(final LocalPlayerService playerService,
            final LocalSceneService sceneService, final CommandObserver observer) {
        return new LocalGameBO(playerService, sceneService, LocalGameObservation.NO_OP_OBSERVER, observer);
    }

    /** Handles login without synchronously waiting. */
    @Override
    public void login(final GameLoginProtocolDTO request) {
        observer.accepted("login", request.traceId);
        CompletionStage<?> stage = playerService.login(new PlayerLoginRequest(
                request.accountId, request.token, request.traceId));
        observe(stage);
    }

    /** Handles scene entry without synchronously waiting. */
    @Override
    public void enterScene(final GameEnterSceneProtocolDTO request) {
        observer.accepted("enterScene", request.traceId);
        observe(sceneService.enterScene(new SceneEnterRequest(request.uid, request.sceneId, request.traceId)));
    }

    /** Handles movement without synchronously waiting. */
    @Override
    public void move(final GameMoveProtocolDTO request) {
        observer.accepted("move", request.traceId);
        observe(sceneService.moveWithResult(new SceneMoveRequest(request.uid, request.sceneId,
                new ScenePosition(request.x, request.y), request.traceId)));
    }

    private void observe(final CompletionStage<?> stage) {
        Objects.requireNonNull(stage, "stage").whenComplete((value, error) -> {
            if (error == null) {
                observation.completed();
            } else {
                observation.failed(error);
            }
        });
    }

    /**
     * Per-command observation hook for adapters and tests.
     *
     * <p>Implementations must not block: they run on the calling business thread before the
     * asynchronous service stage completes. Keep logs and metrics low cardinality.</p>
     *
     * @author zn
     */
    @FunctionalInterface
    public interface CommandObserver {

        /**
         * No-op observer used when a caller does not need per-command hooks.
         */
        CommandObserver NO_OP = (action, traceId) -> {
        };

        /**
         * Records that a business command was accepted.
         *
         * @param action business action name such as {@code login}; never null.
         * @param traceId protocol trace identifier; may be null or blank.
         */
        void accepted(String action, String traceId);
    }
}
