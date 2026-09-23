package group.zn.zero.scene;

import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.game.GameActorGateway;
import group.zn.zero.game.GameRequestContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地场景基础服务。
 *
 * <p>该实现用于阶段 3 单进程原型和业务直接接入。场景实体状态只在 scene lane 内写入；
 * 实现不访问远程 IO，不创建线程池，线程安全。
 *
 * @author zn
 */
public final class LocalSceneService implements SceneService, AutoCloseable {

    /**
     * Actor 投递网关。
     */
    private final GameActorGateway actorGateway;

    /**
     * 场景实体状态表。
     */
    private final Map<String, SceneState> scenes = new ConcurrentHashMap<>();

    /**
     * Actor 注册句柄。
     */
    private final List<ActorSubscription> subscriptions;

    /**
     * 创建本地场景基础服务。
     *
     * @param actorScheduler Actor 调度器；不可为空。
     * @throws NullPointerException 当 Actor 调度器为空时抛出。
     */
    public LocalSceneService(final ActorScheduler actorScheduler) {
        ActorScheduler scheduler = Objects.requireNonNull(actorScheduler, "actorScheduler");
        this.actorGateway = new GameActorGateway(scheduler);
        this.subscriptions = List.of(
                scheduler.register(EnterSceneCommand.class, ActorHandler.sync((context, message) -> {
                    EnterSceneCommand command = (EnterSceneCommand) message.payload();
                    SceneEntityState state = new SceneEntityState(
                            command.request().uid(),
                            command.request().sceneId(),
                            new ScenePosition(0, 0));
                    scenes.computeIfAbsent(command.request().sceneId(), ignored -> new SceneState()).entities
                            .put(command.request().uid(), state);
                    command.result().complete(state);
                })),
                scheduler.register(MoveCommand.class, ActorHandler.sync((context, message) -> {
                    MoveCommand command = (MoveCommand) message.payload();
                    Map<Long, SceneEntityState> entities = scenes.computeIfAbsent(
                            command.request().sceneId(),
                            ignored -> new SceneState()).entities;
                    Optional<SceneEntityState> previous = Optional.ofNullable(entities.get(command.request().uid()));
                    SceneEntityState state = new SceneEntityState(
                            command.request().uid(),
                            command.request().sceneId(),
                            command.request().position());
                    entities.put(command.request().uid(), state);
                    command.result().complete(new SceneMoveResult(previous, state));
                })),
                scheduler.register(LeaveSceneCommand.class, ActorHandler.sync((context, message) -> {
                    LeaveSceneCommand command = (LeaveSceneCommand) message.payload();
                    SceneState scene = scenes.get(command.request().sceneId());
                    Map<Long, SceneEntityState> entities = scene == null ? null : scene.entities;
                    SceneEntityState removed = entities == null ? null : entities.remove(command.request().uid());
                    if (entities != null && entities.isEmpty()) {
                        scenes.remove(command.request().sceneId(), scene);
                    }
                    command.result().complete(new SceneLeaveResult(
                            command.request().sceneId(),
                            command.request().uid(),
                            removed != null,
                            Optional.ofNullable(removed)));
                })),
                scheduler.register(QueryEntityCommand.class, ActorHandler.sync((context, message) -> {
                    QueryEntityCommand command = (QueryEntityCommand) message.payload();
                    command.result().complete(Optional.ofNullable(sceneEntities(command.sceneId())
                            .get(command.uid())));
                })),
                scheduler.register(ListEntitiesCommand.class, ActorHandler.sync((context, message) -> {
                    ListEntitiesCommand command = (ListEntitiesCommand) message.payload();
                    command.result().complete(List.copyOf(sceneEntities(command.sceneId())
                            .values()));
                })));
    }

    /**
     * 进入场景。
     *
     * @param request 进入场景请求；不可为空。
     * @return 场景实体状态；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<SceneEntityState> enterScene(final SceneEnterRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<SceneEntityState> result = new CompletableFuture<>();
        linkDispatch(actorGateway.dispatch(
                GameRequestContext.client(request.traceId()),
                LaneKey.scene(request.sceneId()),
                new EnterSceneCommand(request, result)), result);
        return result;
    }

    /**
     * 移动场景实体。
     *
     * @param request 移动请求；不可为空。
     * @return 场景实体状态；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<SceneEntityState> move(final SceneMoveRequest request) {
        return moveWithResult(request).thenApply(SceneMoveResult::currentState);
    }

    /**
     * 移动场景实体并返回移动结果。
     *
     * @param request 移动请求；不可为空。
     * @return 场景移动结果；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<SceneMoveResult> moveWithResult(final SceneMoveRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<SceneMoveResult> result = new CompletableFuture<>();
        linkDispatch(actorGateway.dispatch(
                GameRequestContext.client(request.traceId()),
                LaneKey.scene(request.sceneId()),
                new MoveCommand(request, result)), result);
        return result;
    }

    /**
     * 离开场景。
     *
     * @param request 离开场景请求；不可为空。
     * @return 离开场景结果；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<SceneLeaveResult> leaveScene(final SceneLeaveRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<SceneLeaveResult> result = new CompletableFuture<>();
        linkDispatch(actorGateway.dispatch(
                GameRequestContext.client(request.traceId()),
                LaneKey.scene(request.sceneId()),
                new LeaveSceneCommand(request, result)), result);
        return result;
    }

    /**
     * 查询场景实体状态。
     *
     * @param sceneId 场景 ID；不可为空。
     * @param uid 玩家 ID。
     * @param traceId 链路追踪 ID；不可为空。
     * @return 场景实体状态 Optional；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<Optional<SceneEntityState>> queryEntity(
            final String sceneId,
            final long uid,
            final String traceId) {
        String currentSceneId = requireText(sceneId, "sceneId");
        String currentTraceId = requireText(traceId, "traceId");
        CompletableFuture<Optional<SceneEntityState>> result = new CompletableFuture<>();
        linkDispatch(actorGateway.dispatch(
                GameRequestContext.gm(currentTraceId),
                LaneKey.scene(currentSceneId),
                new QueryEntityCommand(currentSceneId, uid, result)), result);
        return result;
    }

    /**
     * 查询场景实体列表。
     *
     * @param sceneId 场景 ID；不可为空。
     * @param traceId 链路追踪 ID；不可为空。
     * @return 场景实体状态快照；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<List<SceneEntityState>> listEntities(final String sceneId, final String traceId) {
        String currentSceneId = requireText(sceneId, "sceneId");
        String currentTraceId = requireText(traceId, "traceId");
        CompletableFuture<List<SceneEntityState>> result = new CompletableFuture<>();
        linkDispatch(actorGateway.dispatch(
                GameRequestContext.gm(currentTraceId),
                LaneKey.scene(currentSceneId),
                new ListEntitiesCommand(currentSceneId, result)), result);
        return result;
    }

    /**
     * 关闭服务并取消 Actor handler 注册。
     *
     * <p>数据变更：只取消 handler 注册，不清空已有本地状态。线程安全性由 Actor 调度器保证。
     */
    @Override
    public void close() {
        subscriptions.forEach(ActorSubscription::close);
    }

    /** 仅在对应 Scene Lane 中读取；返回内部表或空表，不能跨 Lane 暴露。 */
    private Map<Long, SceneEntityState> sceneEntities(final String sceneId) {
        SceneState scene = scenes.get(sceneId);
        return scene == null ? Map.of() : scene.entities;
    }

    /** 单个场景的私有可变状态，所有访问均由同一 Lane 串行化。 @author zn */
    private static final class SceneState {
        /** 不向业务公开；外层并发目录负责跨场景安全发布。 */
        private final Map<Long, SceneEntityState> entities = new java.util.HashMap<>();
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    private <T> void linkDispatch(
            final CompletionStage<Void> dispatchStage,
            final CompletableFuture<T> result) {
        dispatchStage.whenComplete((ignored, ex) -> {
            if (ex != null) {
                result.completeExceptionally(ex);
            }
        });
    }

    /**
     * 进入场景命令。
     *
     * @param request 进入场景请求。
     * @param result 场景实体状态 future。
     * @author zn
     */
    private record EnterSceneCommand(
            SceneEnterRequest request,
            CompletableFuture<SceneEntityState> result) {
    }

    /**
     * 移动命令。
     *
     * @param request 移动请求。
     * @param result 场景实体状态 future。
     * @author zn
     */
    private record MoveCommand(
            SceneMoveRequest request,
            CompletableFuture<SceneMoveResult> result) {
    }

    /**
     * 离开场景命令。
     *
     * @param request 离开场景请求。
     * @param result 离开场景结果 future。
     * @author zn
     */
    private record LeaveSceneCommand(
            SceneLeaveRequest request,
            CompletableFuture<SceneLeaveResult> result) {
    }

    /**
     * 场景实体查询命令。
     *
     * @param sceneId 场景 ID。
     * @param uid 玩家 ID。
     * @param result 查询结果 future。
     * @author zn
     */
    private record QueryEntityCommand(
            String sceneId,
            long uid,
            CompletableFuture<Optional<SceneEntityState>> result) {
    }

    /**
     * 场景实体列表查询命令。
     *
     * @param sceneId 场景 ID。
     * @param result 查询结果 future。
     * @author zn
     */
    private record ListEntitiesCommand(
            String sceneId,
            CompletableFuture<List<SceneEntityState>> result) {
    }
}
