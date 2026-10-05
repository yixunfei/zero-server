package group.zn.zero.player;

import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.cache.CacheService;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.core.error.ZeroException;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

    /** 未命中玩家资料时的显式策略。 */
    private final MissingPlayerPolicy missingPlayerPolicy;
    /** 外部注入的 IO 执行域；仅纯内存构造允许直接执行，不由本服务关闭。 */
    private final java.util.concurrent.Executor remoteIoExecutor;

    /**
     * 账号到玩家 ID 的本地会话表。
     */
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    /**
     * 玩家在线档案表。
     */
    private final Map<Long, PlayerProfile> players = new ConcurrentHashMap<>();

    /** 仓库 miss 加载与回填的准入锁；不在锁内调用外部后端或完成业务 future。 */
    private final Object loadGuard = new Object();
    /** 同 UID 共享完整物理加载与回填，最多保留默认缓存并发加载预算个 UID。 */
    private final Map<Long, CompletableFuture<PlayerProfile>> repositoryLoads = new java.util.HashMap<>();
    /** 关闭后禁止新建仓库加载；由 loadGuard 保护。 */
    private boolean closed;

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
                new InMemoryCacheService<>(),
                MissingPlayerPolicy.REJECT);
    }

    /**
     * 创建本地内存玩家服务；按策略决定是否建档，不创建线程。
     * @param actorScheduler 调度器；不可为空。
     * @param uidResolver UID 解析器；不可为空。
     * @param missingPlayerPolicy 缺失策略；不可为空。
     * @throws NullPointerException 必填参数为空。
     */
    public LocalPlayerService(final ActorScheduler actorScheduler, final PlayerUidResolver uidResolver,
            final MissingPlayerPolicy missingPlayerPolicy) {
        this(actorScheduler, uidResolver, new InMemoryCrudRepository<>(), new InMemoryCacheService<>(),
                missingPlayerPolicy);
    }

    /**
     * 创建本地玩家基础服务。
     *
     * <p>传入的 repository / cache 在本地原型中应使用内存实现。若接入真实远程后端，
     * 必须使用带 remoteIoExecutor 的构造器；本构造只接受框架内存实现。
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
        this(actorScheduler, uidResolver, playerRepository, playerCache, MissingPlayerPolicy.REJECT);
    }

    /**
     * 创建可配置缺失玩家策略的本地玩家服务；不创建线程。
     * @param actorScheduler 调度器；不可为空。
     * @param uidResolver UID 解析器；不可为空。
     * @param playerRepository 玩家仓库；实现必须保障原子版本条件写。
     * @param playerCache 玩家缓存；不可为空。
     * @param missingPlayerPolicy 缺失策略；REJECT 不建档，CREATE_DEFAULT 显式允许原型建档。
     * @throws NullPointerException 必填参数为空。
     */
    public LocalPlayerService(
            final ActorScheduler actorScheduler,
            final PlayerUidResolver uidResolver,
            final CrudRepository<Long, PlayerProfile> playerRepository,
            final CacheService<Long, PlayerProfile> playerCache,
            final MissingPlayerPolicy missingPlayerPolicy) {
        this(actorScheduler, uidResolver, playerRepository, playerCache, missingPlayerPolicy,
                localExecutor(playerRepository, playerCache));
    }

    /** 外部后端必须显式提供受管非内联 IO 执行器；线程安全，不负责执行器关闭。
     * @param actorScheduler Actor 调度器。 @param uidResolver UID 解析器。
     * @param playerRepository 数据仓库。 @param playerCache 缓存。 @param remoteIoExecutor 非内联 IO 域。 */
    public LocalPlayerService(final ActorScheduler actorScheduler, final PlayerUidResolver uidResolver,
            final CrudRepository<Long, PlayerProfile> playerRepository, final CacheService<Long, PlayerProfile> playerCache,
            final java.util.concurrent.Executor remoteIoExecutor) {
        this(actorScheduler, uidResolver, playerRepository, playerCache, MissingPlayerPolicy.REJECT, remoteIoExecutor);
    }

    /** 创建带显式 IO 边界的本地玩家服务；跨进程所有权仍由业务负责。
     * @param actorScheduler Actor 调度器。 @param uidResolver UID 解析器。 @param playerRepository 数据仓库。
     * @param playerCache 缓存。 @param missingPlayerPolicy 缺失策略。 @param remoteIoExecutor 受管 IO 域。 */
    public LocalPlayerService(final ActorScheduler actorScheduler, final PlayerUidResolver uidResolver,
            final CrudRepository<Long, PlayerProfile> playerRepository, final CacheService<Long, PlayerProfile> playerCache,
            final MissingPlayerPolicy missingPlayerPolicy, final java.util.concurrent.Executor remoteIoExecutor) {
        this.remoteIoExecutor = Objects.requireNonNull(remoteIoExecutor, "remoteIoExecutor");
        ActorScheduler scheduler = Objects.requireNonNull(actorScheduler, "actorScheduler");
        this.actorGateway = new GameActorGateway(scheduler);
        this.uidResolver = Objects.requireNonNull(uidResolver, "uidResolver");
        this.playerRepository = Objects.requireNonNull(playerRepository, "playerRepository");
        this.playerCache = Objects.requireNonNull(playerCache, "playerCache");
        this.missingPlayerPolicy = Objects.requireNonNull(missingPlayerPolicy, "missingPlayerPolicy");
        this.subscriptions = List.of(
                scheduler.register(LoginCommand.class, ActorHandler.sync((context, message) -> {
                    LoginCommand command = (LoginCommand) message.payload();
                    Long existing = sessions.putIfAbsent(command.request().accountId(), command.uid());
                    if (existing != null) {
                        command.result().completeExceptionally(ZeroException.of(
                                PlayerErrorCode.ACCOUNT_ALREADY_ONLINE,
                                "account already has an online session", null));
                        return;
                    }
                    command.result().complete(new PlayerLoginResult(
                            command.request().accountId(),
                            command.uid(),
                            command.request().traceId()));
                })),
                scheduler.register(LoadPlayerCommand.class, ActorHandler.sync((context, message) -> {
                    LoadPlayerCommand command = (LoadPlayerCommand) message.payload();
                    PlayerProfile profile = command.profile();
                    if (profile.uid() != command.request().uid()) {
                        throw group.zn.zero.core.error.ZeroException.of(
                                group.zn.zero.data.error.DataErrorCode.INVALID_ENTITY, "player snapshot uid mismatch", null);
                    }
                    PlayerProfile existing = players.get(profile.uid());
                    PlayerProfile selected = existing != null && existing.version() >= profile.version() ? existing : profile;
                    players.put(profile.uid(), selected);
                    command.result().complete(selected);
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
        return new LocalPlayerService(actorScheduler, request -> stableUid(request.accountId()));
    }

    /**
     * 使用稳定 hash UID 并显式选择缺失玩家策略，不创建线程。
     * @param actorScheduler 调度器；不可为空。
     * @param missingPlayerPolicy 缺失策略；不可为空。
     * @return 线程安全的本地玩家服务。
     * @throws NullPointerException 必填参数为空。
     */
    public static LocalPlayerService withStableHashUid(
            final ActorScheduler actorScheduler, final MissingPlayerPolicy missingPlayerPolicy) {
        return new LocalPlayerService(actorScheduler, request -> stableUid(request.accountId()),
                new InMemoryCrudRepository<>(), new InMemoryCacheService<>(), missingPlayerPolicy);
    }

    private static long stableUid(final String accountId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(accountId.getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
            return value == 0L ? 1L : value;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * 登录前查询权威仓库，玩家存在或按显式策略建档后才绑定账号到玩家 ID。
     * 身份认证及账号与 UID 的授权关系由业务的 UID 解析器负责。
     *
     * @param request 登录请求；不可为空。
     * @return 登录结果；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<PlayerLoginResult> login(final PlayerLoginRequest request) {
        Objects.requireNonNull(request, "request");
        CompletableFuture<PlayerLoginResult> result = new CompletableFuture<>();
        try {
            CompletableFuture.supplyAsync(() -> uidResolver.resolve(request), remoteIoExecutor)
                    .thenComposeAsync(uid -> loadRepositoryProfile(uid).thenApply(profile -> uid), remoteIoExecutor).whenComplete((uid, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                    return;
                }
                try {
                    linkDispatch(actorGateway.dispatch(
                            GameRequestContext.client(request.traceId()),
                            LaneKey.session(request.accountId()),
                            new LoginCommand(request, uid, result)), result);
                } catch (RuntimeException | Error dispatchFailure) {
                    result.completeExceptionally(dispatchFailure);
                }
            });
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
        }
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
        try {
            CompletableFuture.supplyAsync(() -> loadFromCacheOrRepository(request), remoteIoExecutor)
                    .thenComposeAsync(stage -> stage, remoteIoExecutor).whenComplete((profile, ex) -> {
                if (ex != null) {
                    result.completeExceptionally(ex);
                    return;
                }
                try {
                    linkDispatch(actorGateway.dispatch(
                            GameRequestContext.client(request.traceId()),
                            LaneKey.player(Long.toString(request.uid())),
                            new LoadPlayerCommand(request, profile, result)), result);
                } catch (RuntimeException | Error failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
        }
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
     * <p>取消 handler 注册并结束等待仓库加载的观察者，不清空已有本地状态；线程安全。
     * 已进入后端的物理工作不能强制中断，完成后不会重新挂接已关闭的加载目录。
     */
    @Override
    public void close() {
        List<CompletableFuture<PlayerProfile>> pending;
        synchronized (loadGuard) {
            if (closed) return;
            closed = true;
            pending = List.copyOf(repositoryLoads.values());
            repositoryLoads.clear();
        }
        try {
            subscriptions.forEach(ActorSubscription::close);
        } finally {
            ZeroException failure = closedFailure();
            pending.forEach(result -> result.completeExceptionally(failure));
        }
    }

    private static java.util.concurrent.Executor localExecutor(final Object repository, final Object cache) {
        if (!(repository instanceof InMemoryCrudRepository<?, ?>) || !(cache instanceof InMemoryCacheService<?, ?>)) {
            throw new IllegalArgumentException("custom player backends require an explicit managed IO executor");
        }
        return Runnable::run;
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
        return playerCache.get(request.uid()).thenComposeAsync(cached -> {
            if (cached.isPresent()) {
                return CompletableFuture.completedFuture(checkedProfile(request.uid(), cached.orElseThrow()));
            }
            return loadAndCacheProfile(request.uid());
        }, remoteIoExecutor);
    }

    private CompletionStage<PlayerProfile> loadAndCacheProfile(final long uid) {
        CompletableFuture<PlayerProfile> result;
        synchronized (loadGuard) {
            if (closed) return CompletableFuture.failedFuture(closedFailure());
            CompletableFuture<PlayerProfile> existing = repositoryLoads.get(uid);
            if (existing != null) return existing.copy();
            if (repositoryLoads.size() >= group.zn.zero.cache.CachePolicy.DEFAULT_MAX_CONCURRENT_LOADS) {
                return CompletableFuture.failedFuture(ZeroException.of(
                        group.zn.zero.cache.CacheErrorCode.BACKPRESSURE_REJECTED,
                        "player repository load capacity exceeded", null));
            }
            result = new CompletableFuture<>();
            repositoryLoads.put(uid, result);
        }
        try {
            loadRepositoryProfile(uid).thenComposeAsync(profile ->
                    playerCache.put(uid, profile).thenApply(ignored -> profile), remoteIoExecutor)
                    .whenComplete((profile, failure) -> finishRepositoryLoad(uid, result, profile, failure));
        } catch (RuntimeException | Error failure) {
            finishRepositoryLoad(uid, result, null, failure);
        }
        return result.copy();
    }

    private void finishRepositoryLoad(final long uid, final CompletableFuture<PlayerProfile> result,
            final PlayerProfile profile, final Throwable failure) {
        synchronized (loadGuard) { repositoryLoads.remove(uid, result); }
        if (failure == null) result.complete(profile);
        else result.completeExceptionally(failure);
    }

    private static ZeroException closedFailure() {
        return ZeroException.of(group.zn.zero.core.error.SystemErrorCode.SYSTEM_ERROR,
                "player service closed", null);
    }

    private CompletionStage<PlayerProfile> loadRepositoryProfile(final long uid) {
        return playerRepository.findById(uid).thenComposeAsync(stored -> stored
                .map(profile -> CompletableFuture.completedFuture(checkedProfile(uid, profile)))
                .orElseGet(() -> (missingPlayerPolicy == MissingPlayerPolicy.CREATE_DEFAULT
                        ? createAndStoreDefaultProfile(uid) : failedPlayerNotFound(uid)).toCompletableFuture()), remoteIoExecutor);
    }

    private PlayerProfile checkedProfile(final long uid, final PlayerProfile profile) {
        if (profile.uid() != uid) {
            throw ZeroException.of(group.zn.zero.data.error.DataErrorCode.INVALID_ENTITY,
                    "player snapshot uid mismatch", null);
        }
        return profile;
    }

    private CompletionStage<PlayerProfile> failedPlayerNotFound(final long uid) {
        return CompletableFuture.failedFuture(ZeroException.of(
                PlayerErrorCode.PLAYER_NOT_FOUND, "player profile not found: uid=" + uid, null));
    }

    private CompletionStage<PlayerProfile> createAndStoreDefaultProfile(final long uid) {
        PlayerProfile profile = new PlayerProfile(uid, "player-" + uid, true);
        return playerRepository.save(profile)
                .handle((ignored, failure) -> {
                    if (failure != null && !isVersionConflict(failure)) {
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                    return ignored;
                })
                .thenComposeAsync(ignored -> playerRepository.findById(uid), remoteIoExecutor)
                .thenApply(saved -> checkedProfile(uid, saved.orElseThrow(() ->
                        ZeroException.of(PlayerErrorCode.PLAYER_NOT_FOUND))));
    }

    private boolean isVersionConflict(final Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause instanceof ZeroException zero
                && zero.errorCode() == group.zn.zero.data.error.DataErrorCode.VERSION_CONFLICT;
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
