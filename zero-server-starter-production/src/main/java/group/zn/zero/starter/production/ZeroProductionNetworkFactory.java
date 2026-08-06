package group.zn.zero.starter.production;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkConfig;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.net.lifecycle.ProductionNetworkPolicy;
import group.zn.zero.starter.ZeroRuntimeComponents;
import java.time.Duration;
import java.util.Objects;

/**
 * production starter 网络生命周期显式装配工厂。
 *
 * <p>该工厂只在 {@code zero.net.lifecycle.enabled=true} 时创建 lifecycle，并复用 starter 管理的
 * remote IO / background 执行器、安全日志端口和监控 registry。创建结果仍需显式传入 production TCP 构造。</p>
 *
 * @author zn
 */
public final class ZeroProductionNetworkFactory {

    private ZeroProductionNetworkFactory() {
    }

    /**
     * 判断 production 网络生命周期是否显式启用。
     *
     * @param config 统一配置；不可为空。
     * @return true 表示显式启用；线程安全。
     */
    public static boolean enabled(final ZeroConfig config) {
        return new ProductionConfigResolver(Objects.requireNonNull(config, "config"))
                .enabled(ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED);
    }

    /**
     * 解析用户已确认的 production 网络配置。
     *
     * @param config 统一配置；不可为空，且必须显式启用 lifecycle。
     * @return production 网络配置；不可为空；线程安全。
     * @throws ZeroException 未启用或配置非法时抛出，绑定统一 ErrorCode。
     */
    public static ProductionNetworkConfig resolveConfig(final ZeroConfig config) {
        ZeroConfig current = Objects.requireNonNull(config, "config");
        ProductionConfigResolver resolver = new ProductionConfigResolver(current);
        if (!resolver.enabled(ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED)) {
            throw invalid("production network lifecycle is not explicitly enabled", null);
        }
        try {
            return new ProductionNetworkConfig(
                    resolver.getOrDefault(
                            ZeroProductionRuntimeConfigKeys.NETWORK_LISTENER,
                            ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_LISTENER),
                    Duration.ofMillis(resolver.positiveInt(
                            ZeroProductionRuntimeConfigKeys.NETWORK_HANDSHAKE_TIMEOUT_MILLIS,
                            Math.toIntExact(ProductionNetworkConfig.DEFAULT_HANDSHAKE_TIMEOUT.toMillis()))),
                    Duration.ofMillis(resolver.positiveInt(
                            ZeroProductionRuntimeConfigKeys.NETWORK_AUTHENTICATION_TIMEOUT_MILLIS,
                            Math.toIntExact(ProductionNetworkConfig.DEFAULT_AUTHENTICATION_TIMEOUT.toMillis()))),
                    Duration.ofMillis(resolver.positiveInt(
                            ZeroProductionRuntimeConfigKeys.NETWORK_HEARTBEAT_INTERVAL_MILLIS,
                            Math.toIntExact(ProductionNetworkConfig.DEFAULT_HEARTBEAT_INTERVAL.toMillis()))),
                    resolver.positiveInt(
                            ZeroProductionRuntimeConfigKeys.NETWORK_ALLOWED_MISSED_HEARTBEATS,
                            ProductionNetworkConfig.DEFAULT_ALLOWED_MISSED_HEARTBEATS),
                    Duration.ofMillis(resolver.positiveInt(
                            ZeroProductionRuntimeConfigKeys.NETWORK_RECONNECT_WINDOW_MILLIS,
                            Math.toIntExact(ProductionNetworkConfig.DEFAULT_RECONNECT_WINDOW.toMillis()))),
                    resolver.positiveInt(
                            ZeroProductionRuntimeConfigKeys.NETWORK_MAX_INBOUND_FRAMES,
                            ProductionNetworkConfig.DEFAULT_MAX_INBOUND_FRAMES));
        } catch (RuntimeException ex) {
            throw invalid("invalid production network lifecycle config", ex);
        }
    }

    /**
     * 使用 production starter 默认有界每 IP 限流策略创建 lifecycle。
     *
     * @param config 统一配置；不可为空，必须显式启用。
     * @param policy 握手、鉴权、心跳和重连策略；不可为空。
     * @param components 已构建的 runtime 组件；不可为空；远程 IO 执行器不得内联。
     * @return production lifecycle；不可为空；线程安全。
     * @throws ZeroException 未启用、执行器不安全或配置非法时抛出。
     */
    public static ProductionNetworkLifecycle create(
            final ZeroConfig config,
            final ProductionNetworkPolicy policy,
            final ZeroRuntimeComponents components) {
        ZeroConfig currentConfig = Objects.requireNonNull(config, "config");
        ProductionConfigResolver resolver = new ProductionConfigResolver(currentConfig);
        try {
            NetworkRateLimiter limiter = new ProductionIpConnectionRateLimiter(
                    resolver.positiveInt(
                            ZeroProductionRuntimeConfigKeys.NETWORK_PER_IP_PERMITS_PER_SECOND,
                            ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_PER_IP_PERMITS_PER_SECOND),
                    resolver.positiveInt(
                            ZeroProductionRuntimeConfigKeys.NETWORK_PER_IP_BURST_CAPACITY,
                            ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_PER_IP_BURST_CAPACITY),
                    resolver.positiveInt(
                            ZeroProductionRuntimeConfigKeys.NETWORK_RATE_LIMIT_SLOTS,
                            ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_RATE_LIMIT_SLOTS));
            return create(currentConfig, policy, limiter, components);
        } catch (ZeroException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw invalid("invalid production network rate limit config", ex);
        }
    }

    /**
     * 使用自定义低延迟限流器创建 lifecycle。
     *
     * @param config 统一配置；不可为空，必须显式启用。
     * @param policy 握手、鉴权、心跳和重连策略；不可为空。
     * @param limiter 自定义限流器；不可为空；必须非阻塞且有界。
     * @param components 已构建的 runtime 组件；不可为空；远程 IO 执行器不得内联。
     * @return production lifecycle；不可为空；线程安全。
     * @throws ZeroException 未启用、执行器不安全或配置非法时抛出。
     */
    public static ProductionNetworkLifecycle create(
            final ZeroConfig config,
            final ProductionNetworkPolicy policy,
            final NetworkRateLimiter limiter,
            final ZeroRuntimeComponents components) {
        ZeroRuntimeComponents currentComponents = Objects.requireNonNull(components, "components");
        if (currentComponents.executors().remoteIoMayInline()) {
            throw invalid("production network authentication requires a managed non-inline remote IO executor", null);
        }
        ProductionNetworkConfig networkConfig = resolveConfig(config);
        return new ProductionNetworkLifecycle(
                networkConfig,
                Objects.requireNonNull(policy, "policy"),
                Objects.requireNonNull(limiter, "limiter"),
                new ProductionNetworkTelemetryObserver(
                        currentComponents.logAppender(),
                        currentComponents.monitorRuntime().registry()),
                currentComponents.executors().remoteIoExecutor(),
                currentComponents.executors().backgroundExecutor());
    }

    private static ZeroException invalid(final String message, final Throwable cause) {
        return ZeroException.of(SystemErrorCode.INVALID_ARGUMENT, message, cause);
    }
}
