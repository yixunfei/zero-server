package group.zn.zero.examples.multiplayer;

import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.cache.CacheService;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.player.LocalPlayerService;
import group.zn.zero.player.MissingPlayerPolicy;
import group.zn.zero.player.PlayerLoginRequest;
import group.zn.zero.player.PlayerLoginResult;
import group.zn.zero.player.PlayerLogoutRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令行线程上的玩家生命周期编排；展示职责划分，不是可直接挂载的分布式业务服务。
 *
 * <p>所有公共业务操作必须在创建线程顺序调用，跨线程调用在修改前拒绝。
 * 框架内部会话仍通过 LocalPlayerService 的 Actor 消息修改；外层使用 join 等待完成，
 * 因此必须由普通命令行线程创建和调用，禁止在 Actor/Netty 线程执行。</p>
 *
 * @author zn
 */
public final class PlayerLifecycleService implements AutoCloseable {
    /** 外层顺序编排的拥有者线程。 */
    private final Thread owner = Thread.currentThread();
    /** UID 解析器可能由框架执行域读取，因此账号绑定使用并发容器。 */
    private final Map<String, AccountBinding> accounts = new ConcurrentHashMap<>();
    /** 仅 owner 线程读写的外层会话。 */
    private final Map<String, PlayerSession> sessions = new HashMap<>();
    /** 中心服路由。 */
    private final CenterRouter router;
    /** 角色权威仓库。 */
    private final CrudRepository<Long, PlayerCharacter> characters;
    /** 角色查询缓存。 */
    private final CacheService<Long, PlayerCharacter> characterCache;
    /** 框架玩家基础服务；只由本对象关闭。 */
    private final LocalPlayerService playerService;
    /** 当前示例实例内递增 UID，不用于持久化或多进程分配。 */
    private long nextUid = 1;
    /** owner 线程维护的关闭标记。 */
    private boolean closed;

    /**
     * 在普通调用线程创建示例；不取得 scheduler、Repository 或 Cache 的关闭权。
     * @param scheduler 框架 Actor 调度器。
     * @param router 中心路由器。
     * @param characters 角色仓库；组合根使用全新内存实例。
     * @param characterCache 角色缓存；组合根使用全新内存实例。
     * @throws NullPointerException 任一依赖为空。
     */
    public PlayerLifecycleService(final ActorScheduler scheduler, final CenterRouter router,
            final CrudRepository<Long, PlayerCharacter> characters,
            final CacheService<Long, PlayerCharacter> characterCache) {
        this.router = Objects.requireNonNull(router, "router");
        this.characters = Objects.requireNonNull(characters, "characters");
        this.characterCache = Objects.requireNonNull(characterCache, "characterCache");
        this.playerService = new LocalPlayerService(scheduler,
                request -> authenticate(request).uid(), MissingPlayerPolicy.CREATE_DEFAULT);
    }

    /**
     * 注册唯一账号，分配当前实例内唯一 UID；仅 owner 线程，修改账号表。
     * @param accountId 非空账号。
     * @return 不可空、不可变账号绑定。
     * @throws MultiServerError 输入非法、账号已存在、线程错误或服务已关闭。
     */
    public AccountBinding register(final String accountId) {
        requireOpen();
        requireText(accountId, "accountId");
        if (accounts.containsKey(accountId)) {
            throw new MultiServerError(MultiServerErrorCode.ACCOUNT_EXISTS);
        }
        AccountBinding created = new AccountBinding(accountId, nextUid);
        nextUid = Math.incrementExact(nextUid);
        accounts.put(accountId, created);
        return created;
    }

    /**
     * 认证后登录并绑定路由，重复登录保留完整会话；仅 owner 线程，可能修改会话表。
     * @param accountId 非空已注册账号。
     * @param token 本地教学 token，格式为 token-账号；不提供真实认证安全。
     * @param traceId 非空追踪标识。
     * @return 不可空、不可变会话。
     * @throws ZeroException 认证、路由、框架登录失败，或线程/生命周期约束不满足。
     */
    public PlayerSession login(final String accountId, final String token, final String traceId) {
        requireOpen();
        PlayerLoginRequest request = request(accountId, token, traceId);
        AccountBinding binding = authenticate(request);
        String logic = router.allocate(binding.uid());
        PlayerLoginResult result = await(playerService.login(request));
        PlayerSession session = new PlayerSession(accountId, result.uid(), result.sessionId(), logic);
        sessions.put(accountId, session);
        return session;
    }

    /**
     * 为当前有效会话创建角色；仅 owner 线程，写仓库并回填已提交版本。
     * @param session 登录返回的完整会话；不可伪造或已失效。
     * @param name 非空角色名。
     * @return 不可空的已提交角色快照。
     * @throws ZeroException 会话非法、角色已存在或数据操作失败；缓存失败不撤销已提交角色。
     */
    public PlayerCharacter createCharacter(final PlayerSession session, final String name) {
        requireOpen();
        requireCurrentSession(session);
        requireText(name, "name");
        // 缓存不能决定是否允许创建，权威仓库的版本条件写还需防止竞争插入。
        if (await(characters.findById(session.uid())).isPresent()) {
            throw new MultiServerError(MultiServerErrorCode.CHARACTER_EXISTS);
        }
        await(characters.save(new PlayerCharacter(session.uid(), name)));
        PlayerCharacter saved = await(characters.findById(session.uid())).orElseThrow(
                () -> new MultiServerError(MultiServerErrorCode.INVALID_STATE));
        await(characterCache.put(session.uid(), saved));
        return saved;
    }

    /**
     * 查询角色并在缓存未命中时回源；仅 owner 线程，不写权威仓库。
     * @param uid 正数玩家 ID；内部查询入口，网络调用须先校验访问权限。
     * @return 不可空 Optional；可能为空，包含不可变快照；可能回填缓存。
     * @throws ZeroException 参数、线程或依赖操作失败。
     */
    public Optional<PlayerCharacter> loadCharacter(final long uid) {
        requireOpen();
        if (uid <= 0) {
            throw new MultiServerError(MultiServerErrorCode.INVALID_REQUEST);
        }
        Optional<PlayerCharacter> cached = await(characterCache.get(uid));
        if (cached.isPresent()) {
            return cached;
        }
        Optional<PlayerCharacter> loaded = await(characters.findById(uid));
        loaded.ifPresent(value -> await(characterCache.put(uid, value)));
        return loaded;
    }

    /**
     * 按完整会话身份断线；仅 owner 线程，旧会话不修改新会话。
     * @param session 非空会话。
     * @param traceId 非空追踪标识。
     * @return 是否完成框架解绑；过期会话返回 false。
     * @throws ZeroException 输入、线程、框架解绑失败；失败时保留外层会话以便诊断/重试。
     */
    public boolean disconnect(final PlayerSession session, final String traceId) {
        requireOpen();
        requireText(traceId, "traceId");
        Objects.requireNonNull(session, "session");
        if (!session.equals(sessions.get(session.accountId()))) {
            return false;
        }
        boolean loggedOut = await(playerService.logout(new PlayerLogoutRequest(
                session.accountId(), session.uid(), session.sessionId(), traceId)));
        sessions.remove(session.accountId(), session);
        return loggedOut;
    }

    /**
     * 先完整认证，再断线并重新登录；仅 owner 线程，成功后替换会话。
     * @param accountId 已注册账号。
     * @param token 教学认证 token。
     * @param traceId 非空追踪标识。
     * @return 新不可变会话。
     * @throws ZeroException 认证失败不修改旧会话；后续基础设施失败则向上传播，不承诺回滚登录。
     */
    public PlayerSession reconnect(final String accountId, final String token, final String traceId) {
        requireOpen();
        authenticate(request(accountId, token, traceId));
        PlayerSession old = sessions.get(accountId);
        if (old != null) {
            disconnect(old, traceId + "-cleanup");
        }
        return login(accountId, token, traceId);
    }

    /**
     * 查询当前会话；仅 owner 线程，无数据变更。
     * @param accountId 非空账号。
     * @return 不可空 Optional；可能为空，内容不可变。
     * @throws MultiServerError 参数、线程或生命周期非法。
     */
    public Optional<PlayerSession> session(final String accountId) {
        requireOpen();
        requireText(accountId, "accountId");
        return Optional.ofNullable(sessions.get(accountId));
    }

    /**
     * 查询账号绑定；仅 owner 线程，无数据变更。
     * @param accountId 非空账号。
     * @return 不可空 Optional；可能为空，内容不可变。
     * @throws MultiServerError 参数、线程或生命周期非法。
     */
    public Optional<AccountBinding> account(final String accountId) {
        requireOpen();
        requireText(accountId, "accountId");
        return Optional.ofNullable(accounts.get(accountId));
    }

    /**
     * 关闭框架玩家服务并清空本地绑定；仅 owner 线程，可重复调用，不关闭外部依赖。
     * @throws MultiServerError 跨线程调用。
     */
    @Override
    public void close() {
        requireOwner();
        if (!closed) {
            playerService.close();
            closed = true;
            sessions.clear();
            accounts.clear();
        }
    }

    private AccountBinding authenticate(final PlayerLoginRequest request) {
        AccountBinding binding = accounts.get(request.accountId());
        if (binding == null || !request.token().equals("token-" + request.accountId())) {
            throw new MultiServerError(MultiServerErrorCode.AUTHENTICATION_FAILED);
        }
        return binding;
    }

    private void requireCurrentSession(final PlayerSession session) {
        if (session == null || !session.equals(sessions.get(session.accountId()))
                || !new AccountBinding(session.accountId(), session.uid()).equals(accounts.get(session.accountId()))) {
            throw new MultiServerError(MultiServerErrorCode.STALE_SESSION);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new MultiServerError(MultiServerErrorCode.WRONG_THREAD);
        }
    }

    private void requireOpen() {
        requireOwner();
        if (closed) {
            throw new MultiServerError(MultiServerErrorCode.INVALID_STATE);
        }
    }

    private static PlayerLoginRequest request(final String account, final String token, final String trace) {
        requireText(account, "accountId");
        requireText(token, "token");
        requireText(trace, "traceId");
        return new PlayerLoginRequest(account, token, trace);
    }

    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new MultiServerError(MultiServerErrorCode.INVALID_REQUEST, name + " must not be blank");
        }
    }

    private static <T> T await(final CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof ZeroException zero) {
                throw zero;
            }
            throw failure;
        }
    }
}
