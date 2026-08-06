package group.zn.zero.net.lifecycle;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * 显式 opt-in 的生产网络生命周期装配。
 *
 * <p>该对象不可变、线程安全，可由同一 TCP 服务器的多个连接共享。鉴权执行器必须由 starter
 * 或框架统一管理；observer 执行器用于隔离日志与指标下游，单连接会话会在其上保持事件提交顺序，
 * 不要求执行器自身为单线程。业务代码不得在这里创建线程池。</p>
 *
 * @author zn
 */
public final class ProductionNetworkLifecycle {

    /** 生产生命周期配置。 */
    private final ProductionNetworkConfig config;
    /** 握手、鉴权、心跳和重连策略。 */
    private final ProductionNetworkPolicy policy;
    /** 连接与 frame 限流器。 */
    private final NetworkRateLimiter rateLimiter;
    /** 生命周期 observer。 */
    private final ConnectionLifecycleObserver observer;
    /** 受管鉴权执行器。 */
    private final Executor authenticationExecutor;
    /** 受管 observer 执行器。 */
    private final Executor observerExecutor;

    /**
     * 创建不带限流和观测下游的最小 production lifecycle。
     *
     * @param config 生产网络配置；不可为空。
     * @param policy 握手、鉴权、心跳和重连策略；不可为空。
     * @param authenticationExecutor 受管鉴权执行器；不可为空；调用方负责生命周期。
     */
    public ProductionNetworkLifecycle(
            final ProductionNetworkConfig config,
            final ProductionNetworkPolicy policy,
            final Executor authenticationExecutor) {
        this(
                config,
                policy,
                NetworkRateLimiter.permitAll(),
                ConnectionLifecycleObserver.noOp(),
                authenticationExecutor,
                Runnable::run);
    }

    /**
     * 创建完整 production lifecycle。
     *
     * @param config 生产网络配置；不可为空。
     * @param policy 握手、鉴权、心跳和重连策略；不可为空。
     * @param rateLimiter 连接与 frame 限流器；不可为空。
     * @param observer 生命周期 observer；不可为空。
     * @param authenticationExecutor 受管鉴权执行器；不可为空；调用方负责生命周期。
     * @param observerExecutor 受管 observer 执行器；不可为空；可并发执行不同连接，单连接顺序由会话保证；
     *        调用方负责生命周期。
     */
    public ProductionNetworkLifecycle(
            final ProductionNetworkConfig config,
            final ProductionNetworkPolicy policy,
            final NetworkRateLimiter rateLimiter,
            final ConnectionLifecycleObserver observer,
            final Executor authenticationExecutor,
            final Executor observerExecutor) {
        this.config = Objects.requireNonNull(config, "config");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.authenticationExecutor = Objects.requireNonNull(authenticationExecutor, "authenticationExecutor");
        this.observerExecutor = Objects.requireNonNull(observerExecutor, "observerExecutor");
    }

    /** @return 生产网络配置；不可为空；线程安全。 */
    public ProductionNetworkConfig config() {
        return config;
    }

    /** @return 生产网络策略；不可为空；线程安全。 */
    public ProductionNetworkPolicy policy() {
        return policy;
    }

    /** @return 网络限流器；不可为空；线程安全。 */
    public NetworkRateLimiter rateLimiter() {
        return rateLimiter;
    }

    /** @return 生命周期 observer；不可为空；线程安全。 */
    public ConnectionLifecycleObserver observer() {
        return observer;
    }

    /** @return 受管鉴权执行器；不可为空；调用方不得关闭。 */
    public Executor authenticationExecutor() {
        return authenticationExecutor;
    }

    /** @return 受管 observer 执行器；不可为空；调用方不得关闭；无需自行保证单连接 FIFO。 */
    public Executor observerExecutor() {
        return observerExecutor;
    }
}
