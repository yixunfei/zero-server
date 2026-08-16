package group.zn.zero.starter.production;

import group.zn.zero.cache.CacheService;
import group.zn.zero.data.DataService;
import group.zn.zero.discovery.nacos.ServiceDiscovery;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.rpc.discovery.RpcServiceResolver;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import group.zn.zero.rpc.spi.RpcTransport;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.starter.LocalRuntimeCapabilities;

/**
 * Production 组合根对跨 Adapter 中立接口的 typed key 绑定。
 *
 * <p>业务服务应通过构造器接收这些中立接口，不应持有 Mongo、Redis、Kafka、JDBC 或 Nacos 驱动对象。</p>
 *
 * @author zn
 */
public final class ProductionRuntimeCapabilities {

    public static final ComponentKey<RpcTransport> RPC_TRANSPORT = LocalRuntimeCapabilities.RPC_TRANSPORT;
    public static final ComponentKey<RpcHandlerRegistry> RPC_HANDLER_REGISTRY =
            LocalRuntimeCapabilities.RPC_HANDLER_REGISTRY;
    public static final ComponentSetKey<DataService> DATA_SERVICES = ComponentSetKey.multiple(
            StandardRuntimeCapabilityModel.DATA_SERVICES, DataService.class);
    public static final ComponentKey<CacheService<Object, Object>> CACHE_SERVICE =
            LocalRuntimeCapabilities.CACHE_SERVICE;
    public static final ComponentKey<ServiceDiscovery> SERVICE_DISCOVERY = ComponentKey.single(
            StandardRuntimeCapabilityModel.SERVICE_DISCOVERY, ServiceDiscovery.class);
    public static final ComponentKey<RpcServiceResolver> RPC_SERVICE_RESOLVER = ComponentKey.single(
            StandardRuntimeCapabilityModel.RPC_SERVICE_RESOLVER, RpcServiceResolver.class);
    public static final ComponentKey<ProductionNetworkLifecycle> NETWORK_LIFECYCLE = ComponentKey.single(
            StandardRuntimeCapabilityModel.NETWORK_LIFECYCLE, ProductionNetworkLifecycle.class);

    private ProductionRuntimeCapabilities() {
    }
}
