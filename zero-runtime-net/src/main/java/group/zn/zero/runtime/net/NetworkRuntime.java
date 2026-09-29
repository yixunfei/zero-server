package group.zn.zero.runtime.net;

import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObserver;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.net.lifecycle.ProductionNetworkPolicy;
import group.zn.zero.security.SecurityChain;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.netty.NettyIoResources;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.production.ProductionModule;
import group.zn.zero.runtime.production.ProductionModuleFactory;
import java.util.List;
import java.util.Objects;

/**
 * 最小生产连接生命周期装配入口；策略和非内联受管执行器由调用方提供。
 *
 * @author zn
 */
public final class NetworkRuntime {
    /** Runtime 拥有的可选 IO 资源；业务 handler 不访问。 */
    public static final ComponentKey<NettyIoResources> IO_RESOURCES = ComponentKey.single(
            StandardRuntimeCapabilityModel.NETWORK_IO, NettyIoResources.class);
    /** 显式启用的连接生命周期能力。 */
    public static final ComponentKey<ProductionNetworkLifecycle> NETWORK_LIFECYCLE = ComponentKey.single(
            StandardRuntimeCapabilityModel.NETWORK_LIFECYCLE, ProductionNetworkLifecycle.class);

    private NetworkRuntime() {
    }

    /**
     * 创建显式 IO 资源 provider；planning 不创建线程，create 后立即登记 rollback/close。
     * @param options 传输与线程预算；不可为空。默认不安装，服务器可以继续使用独占资源。
     * @return 不可变 provider；线程安全。server provider 应 require(IO_RESOURCES) 以建立关闭顺序。
     * @throws NullPointerException 配置为空。
     */
    public static RuntimeComponentProvider ioResources(final ServerOptions options) {
        Objects.requireNonNull(options, "options");
        return RuntimeProviders.create(
                ComponentDescriptor.builder(StandardRuntimeCapabilityModel.NETWORK_IO_PROVIDER)
                        .provide(IO_RESOURCES).kind(ComponentKind.FOUNDATION).build(),
                context -> ComponentContribution.builder()
                        .bind(IO_RESOURCES, context.resources().register(NettyIoResources.open(options)))
                        .build());
    }

    /**
     * 创建显式选择 IO 资源的装配模块；可直接 install 到 local/production composition。
     * @param options 传输与线程预算，不可为空；构造模块不创建资源。
     * @return 不可变模块；线程安全，资源由创建它的 runtime 关闭。
     * @throws NullPointerException 配置为空。
     */
    public static RuntimeModule ioModule(final ServerOptions options) {
        return RuntimeModule.of("zero.net.io", List.of(ioResources(options)), IO_RESOURCES);
    }

    /**
     * 创建不附加安全链与观测下游的模块；鉴权由 policy 决定。
     * @param policy 启用生命周期时必须提供；实现必须线程安全。
     * @param limiter 可选限流器；为空时不限制连接与帧频率。
     * @return 不可变模块工厂；调用不分配线程或连接。
     */
    public static ProductionModuleFactory module(
            final ProductionNetworkPolicy policy, final NetworkRateLimiter limiter) {
        return module(policy, limiter, null);
    }

    /**
     * 创建带可选安全链的模块；非空安全链负责鉴权、TLS 要求与重放检查。
     * @param policy 启用生命周期时必须提供；实现必须线程安全。
     * @param limiter 可选限流器；为空时不限制连接与帧频率。
     * @param securityChain 可选安全链；为空时执行 policy 的鉴权。
     * @return 不可变模块工厂；调用不分配线程或连接。
     */
    public static ProductionModuleFactory module(
            final ProductionNetworkPolicy policy,
            final NetworkRateLimiter limiter,
            final SecurityChain securityChain) {
        return module(policy, limiter, securityChain, null);
    }

    /**
     * 创建按需定制的生产网络模块；所有附加策略必须显式注入。
     * @param policy 启用生命周期时必须提供；实现必须线程安全。
     * @param limiter 可选限流器；为空时不限制连接与帧频率。
     * @param securityChain 可选安全链；为空时执行 policy 的鉴权，非空时严格执行安全链。
     * @param observer 可选观测器；为空时使用 no-op，不根据其他模块自动启用。
     * @return 不可变模块工厂；调用不分配线程或连接，下游资源由调用方管理。
     */
    public static ProductionModuleFactory module(
            final ProductionNetworkPolicy policy,
            final NetworkRateLimiter limiter,
            final SecurityChain securityChain,
            final ConnectionLifecycleObserver observer) {
        return context -> {
            ProductionNetworkProvider.Resolution resolved = ProductionNetworkProvider.resolve(
                    context.resolver(), policy, limiter, securityChain, observer);
            return new ProductionModule(
                    resolved.enabled() ? List.of(resolved.provider()) : List.of(),
                    resolved.enabled() ? resolved.provider().configSources() : List.of(),
                    resolved.enabled() ? List.of(resolved.provider().diagnostic()) : List.of());
        };
    }
}
