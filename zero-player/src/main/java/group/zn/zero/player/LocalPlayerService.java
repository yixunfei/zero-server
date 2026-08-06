package group.zn.zero.player;

import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.cache.CacheService;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.InMemoryCrudRepository;
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
 * 本地玩家基础服务。
 *
 * <p>该实现用于阶段 3 单进程原型和业务直接接入。账号会话在 session lane 写入，
 * 玩家在线档案在 player lane 写入；玩家加载可通过统一 Repository / Cache 抽象扩展。
 * 默认构造使用本地内存实现，不创建线程池，线程安全。
 *
 * @author zn
 */
public final class LocalPlayerService implements PlayerService, AutoCloseable {

    /**
     * Actor 投递网关。
     */
    private final GameActorGateway actorGateway;

    /**
     * UID 解析器。
     */
    private final PlayerUidResolver uidResolver;

    /**
     * 玩家仓库。
     */
    private final CrudRepository<Long, PlayerProfile> playerRepository;

    /**
     * 玩家缓存。
     */
    private final CacheService<Long, PlayerProfile> playerCache;

    /**
     * 账号到玩家 ID 的本地会话表。
     */
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    /**
     * 玩家在线档案表。
     */
    private final Map<Long, PlayerProfile> players = new ConcurrentHashMap<>();

    /**
     * Actor 注册句柄。
     */
    private final List<ActorSubscription> subscriptions;

    /**
     * 创建本地玩家基础服务。
     *
     * @param actorScheduler Actor 调度器；不可为空。
     * @param uidResolver UID 解析器；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public LocalPlayerService(
            final ActorScheduler actorScheduler,
            final PlayerUidResolver uidResolver) {
        this(
                actorScheduler,
                uidResolver,
                new InMemoryCrudRepository<>(),
                new InMemoryCacheService<>());
    }

    /**
     * 创建本地玩家基础服务。
     *
     * <p>传入的 repository / cache 在本地原型中应使用内存实现。若接入真实远程后端，
     * 调用方必须确保远程 IO 不在 Actor 热路径中阻塞执行。
     *
     * @param actorScheduler Actor 调度器；不可为空。
     * @param uidResolver UID 解析器；不可为空。
     * @param playerRepository 玩家仓库；不可为空。
     * @param playerCache 玩家缓存；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public LocalPlayerService(
            final ActorScheduler actorScheduler,
            final PlayerUidResolver uidResolver,
            final CrudRepository<Long, PlayerProfile> playerRepository,
            final CacheService<Long, PlayerProfile> playerCache) {
        ActorScheduler scheduler = Objects.requireNonNull(actorScheduler, "actorScheduler");
        this.actorGateway = new GameActorGateway(scheduler);
        this.uidResolver = Objects.requireNonNull(uidResolver, "uidResolver");
        this.playerRepository = Objects.requireNonNull(playerRepository, "playerRepository");
        this.playerCache = Objects.requireNonNull(playerCache, "playerCache");
        this.subscriptions = List.of(
                scheduler.register(LoginCommand.class, ActorHandler.sync((context, message) -> {
                    LoginCommand command = (LoginCommand) message.payload();
                    sessions.put(command.request().accountId(), command.uid());
                    command.result().complete(new PlayerLoginResult(
                            command.request().accountId(),
                            command.uid(),
                            command.request().traceId()));
                })),
                scheduler.register(LoadPlayerCommand.class, ActorHandler.sync((context, message) -> {
                    LoadPlayerCommand command = (LoadPlayerCommand) message.payload();
                    PlayerProfile profile = command.profile();
                    players.put(profile.uid(), profile);
                    command.result().complete(profile);
                })),
                scheduler.register(QueryPlayerCommand.class, ActorHandler.sync((context, message) -> {
                    QueryPlayerCommand command = (QueryPlayerCommand) message.payload();
                    command.result().complete(Optional.ofNullable(players.get(command.uid())));
                })),
                scheduler.register(QuerySessionCommand.class, ActorHandler.sync((context, message) -> {
                    QuerySessionCommand command = (QuerySessionCommand) message.payload();
                    command.result().complete(Optional.ofNullable(sessions.get(command.accountId())));
                })));
    }

    /**
     * 创建使用稳定 hash UID 的本地玩家服务。
     *
     * @param actorScheduler Actor 调度器；不可为空。
     * @return 本地玩家服务；不可为空；线程安全。
     */
    public static LocalPlayerService withStableHashUid(final ActorScheduler actorScheduler) {
        return new LocalPlayerService(actorScheduler, request -> Math.abs((long) request.accountId().hashCode()));
    }

    /**
     * 登录并绑定账号到玩家 ID。
     *
     * @param request 登录请求；不可为空。
     * @return 登录结果；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<PlayerLoginResult> login(final PlayerLoginRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<PlayerLoginResult> result = new CompletableFuture<>();
        long uid = uidResolver.resolve(request);
        linkDispatch(actorGateway.dispatch(
                GameRequestContext.client(request.traceId()),
                LaneKey.session(request.accountId()),
                new LoginCommand(request, uid, result)), result);
        return result;
    }

    /**
     * 加载玩家在线数据。
     *
     * @param request 玩家加载请求；不可为空。
     * @return 玩家在线档案；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<PlayerProfile> loadPlayer(final PlayerLoadRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<PlayerProfile> result = new CompletableFuture<>();
        loadFromCacheOrRepository(request).whenComplete((profile, ex) -> {
            if (ex != null) {
                result.completeExceptionally(ex);
                return;
            }
            linkDispatch(actorGateway.dispatch(
                    GameRequestContext.client(request.traceId()),
                    LaneKey.player(Long.toString(request.uid())),
                    new LoadPlayerCommand(request, profile, result)), result);
        });
        return result;
    }

    /**
     * 查询玩家在线档案。
     *
     * @param uid 玩家 ID。
     * @param traceId 链路追踪 ID；不可为空。
     * @return 玩家档案 Optional；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<Optional<PlayerProfile>> queryPlayer(final long uid, final String traceId) {
        String currentTraceId = requireText(traceId, "traceId");
        CompletableFuture<Optional<PlayerProfile>> result = new CompletableFuture<>();
        linkDispatch(actorGateway.dispatch(
                GameRequestContext.gm(currentTraceId),
                LaneKey.player(Long.toString(uid)),
                new QueryPlayerCommand(uid, result)), result);
        return result;
    }

    /**
     * 查询账号绑定的玩家 ID。
     *
     * @param accountId 账号 ID；不可为空。
     * @param traceId 链路追踪 ID；不可为空。
     * @return 玩家 ID Optional；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<Optional<Long>> querySession(final String accountId, final String traceId) {
        String currentAccountId = requireText(accountId, "accountId");
        String currentTraceId = requireText(traceId, "traceId");
        CompletableFuture<Optional<Long>> result = new CompletableFuture<>();
        linkDispatch(actorGateway.dispatch(
                GameRequestContext.gm(currentTraceId),
                LaneKey.session(currentAccountId),
                new QuerySessionCommand(currentAccountId, result)), result);
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

    private CompletionStage<PlayerProfile> loadFromCacheOrRepository(final PlayerLoadRequest request) {
        return playerCache.get(request.uid()).thenCompose(cached -> {
            if (cached.isPresent()) {
                return java.util.concurrent.CompletableFuture.completedFuture(cached.orElseThrow());
            }
            return playerRepository.findById(request.uid())
                    .thenCompose(stored -> stored
                            .map(profile -> playerCache.put(request.uid(), profile)
                                    .thenApply(ignored -> profile))
                            .orElseGet(() -> createAndStoreDefaultProfile(request.uid())));
        });
    }

    private CompletionStage<PlayerProfile> createAndStoreDefaultProfile(final long uid) {
        PlayerProfile profile = new PlayerProfile(uid, "player-" + uid, true);
        return playerRepository.save(profile)
                .thenCompose(ignored -> playerRepository.findById(uid))
                .thenCompose(saved -> {
                    PlayerProfile current = saved.orElseThrow();
                    return playerCache.put(uid, current).thenApply(ignored -> current);
                });
    }

    /**
     * 登录命令。
     *
     * @param request 登录请求。
     * @param uid 玩家 ID。
     * @param result 登录结果 future。
     * @author zn
     */
    private record LoginCommand(
            PlayerLoginRequest request,
            long uid,
            CompletableFuture<PlayerLoginResult> result) {
    }

    /**
     * 玩家加载命令。
     *
     * @param request 玩家加载请求。
     * @param result 玩家档案 future。
     * @author zn
     */
    private record LoadPlayerCommand(
            PlayerLoadRequest request,
            PlayerProfile profile,
            CompletableFuture<PlayerProfile> result) {
    }

    /**
     * 玩家查询命令。
     *
     * @param uid 玩家 ID。
     * @param result 查询结果 future。
     * @author zn
     */
    private record QueryPlayerCommand(
            long uid,
            CompletableFuture<Optional<PlayerProfile>> result) {
    }

    /**
     * 会话查询命令。
     *
     * @param accountId 账号 ID。
     * @param result 查询结果 future。
     * @author zn
     */
    private record QuerySessionCommand(
            String accountId,
            CompletableFuture<Optional<Long>> result) {
    }
}
