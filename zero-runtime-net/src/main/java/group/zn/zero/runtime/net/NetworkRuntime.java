package group.zn.zero.runtime.net;

import group.zn.zero.net.lifecycle.NetworkRateLimiter;
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
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.util.List;
import java.util.Objects;

/** Production connection lifecycle integration; policy and non-inline executors remain explicit. */
public final class NetworkRuntime {
    /** Runtime 拥有的可选 IO 资源；业务 handler 不访问。 */
    public static final ComponentKey<NettyIoResources> IO_RESOURCES = ComponentKey.single(
            StandardRuntimeCapabilityModel.NETWORK_IO, NettyIoResources.class);
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

    public static ProductionModuleFactory module(
            final ProductionNetworkPolicy policy, final NetworkRateLimiter limiter) {
        return module(policy, limiter, null);
    }

    public static ProductionModuleFactory module(
            final ProductionNetworkPolicy policy,
            final NetworkRateLimiter limiter,
            final SecurityChain securityChain) {
        return context -> {
            ProductionNetworkProvider.Resolution resolved = ProductionNetworkProvider.resolve(
                    context.resolver(), policy, limiter, securityChain);
            return new ProductionModule(
                    resolved.enabled() ? List.of(resolved.provider()) : List.of(),
                    resolved.enabled() ? resolved.provider().configSources() : List.of(),
                    resolved.enabled() ? List.of(resolved.provider().diagnostic()) : List.of());
        };
    }
}
