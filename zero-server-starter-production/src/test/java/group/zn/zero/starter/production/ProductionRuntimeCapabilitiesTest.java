package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheService;
import group.zn.zero.data.DataService;
import group.zn.zero.discovery.nacos.ServiceDiscovery;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.rpc.discovery.RpcServiceResolver;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import group.zn.zero.rpc.spi.RpcTransport;
import group.zn.zero.runtime.api.BindingCardinality;
import group.zn.zero.runtime.capability.MavenCoordinate;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Production typed capability 与中立逻辑模型的对齐测试。 */
class ProductionRuntimeCapabilitiesTest {

    @Test
    void typedKeysShouldExposeOnlyNeutralBusinessInterfaces() {
        assertKey(
                StandardRuntimeCapabilityModel.RPC_TRANSPORT,
                RpcTransport.class,
                BindingCardinality.SINGLE,
                ProductionRuntimeCapabilities.RPC_TRANSPORT);
        assertKey(
                StandardRuntimeCapabilityModel.RPC_HANDLER_REGISTRY,
                RpcHandlerRegistry.class,
                BindingCardinality.SINGLE,
                ProductionRuntimeCapabilities.RPC_HANDLER_REGISTRY);
        assertKey(
                StandardRuntimeCapabilityModel.DATA_SERVICES,
                DataService.class,
                BindingCardinality.MULTIPLE,
                ProductionRuntimeCapabilities.DATA_SERVICES);
        assertKey(
                StandardRuntimeCapabilityModel.CACHE_SERVICE,
                CacheService.class,
                BindingCardinality.SINGLE,
                ProductionRuntimeCapabilities.CACHE_SERVICE);
        assertKey(
                StandardRuntimeCapabilityModel.SERVICE_DISCOVERY,
                ServiceDiscovery.class,
                BindingCardinality.SINGLE,
                ProductionRuntimeCapabilities.SERVICE_DISCOVERY);
        assertKey(
                StandardRuntimeCapabilityModel.RPC_SERVICE_RESOLVER,
                RpcServiceResolver.class,
                BindingCardinality.SINGLE,
                ProductionRuntimeCapabilities.RPC_SERVICE_RESOLVER);
        assertKey(
                StandardRuntimeCapabilityModel.NETWORK_LIFECYCLE,
                ProductionNetworkLifecycle.class,
                BindingCardinality.SINGLE,
                ProductionRuntimeCapabilities.NETWORK_LIFECYCLE);
    }

    @Test
    void vocabularyShouldAdvertiseOnlyImplementedProductionProviders() {
        assertEquals(
                List.of(
                        StandardRuntimeCapabilityModel.PRODUCTION_KAFKA_RPC,
                        StandardRuntimeCapabilityModel.PRODUCTION_MONGO_DATA,
                        StandardRuntimeCapabilityModel.PRODUCTION_NACOS_DISCOVERY,
                        StandardRuntimeCapabilityModel.PRODUCTION_NACOS_RPC_RESOLVER,
                        StandardRuntimeCapabilityModel.PRODUCTION_NETWORK_LIFECYCLE,
                        StandardRuntimeCapabilityModel.PRODUCTION_POSTGRESQL_DATA,
                        StandardRuntimeCapabilityModel.PRODUCTION_REDIS_CACHE,
                        StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA,
                        StandardRuntimeCapabilityModel.PRODUCTION_REDIS_RESOURCE),
                StandardRuntimeCapabilityModel.instance().providers().stream()
                        .map(provider -> provider.providerId())
                        .filter(providerId -> providerId.value().contains("production"))
                        .toList());
        assertTrue(StandardRuntimeCapabilityModel.instance()
                .provider(StandardRuntimeCapabilityModel.PRODUCTION_KAFKA_RPC)
                .orElseThrow()
                .profiles()
                .contains(StandardRuntimeCapabilityModel.PROFILE_PRODUCTION));
        assertEquals(
                List.of(
                        MavenCoordinate.zero("zero-discovery-nacos"),
                        MavenCoordinate.zero("zero-rpc")),
                StandardRuntimeCapabilityModel.instance().artifactsForProviders(List.of(
                        StandardRuntimeCapabilityModel.PRODUCTION_NACOS_DISCOVERY,
                        StandardRuntimeCapabilityModel.PRODUCTION_NACOS_RPC_RESOLVER)));
        assertEquals(
                List.of(
                        MavenCoordinate.zero("zero-core"),
                        MavenCoordinate.zero("zero-log"),
                        MavenCoordinate.zero("zero-monitor"),
                        MavenCoordinate.zero("zero-net"),
                        MavenCoordinate.zero("zero-server-starter")),
                StandardRuntimeCapabilityModel.instance().artifactsForProviders(
                        List.of(StandardRuntimeCapabilityModel.PRODUCTION_NETWORK_LIFECYCLE)));
        assertEquals(
                List.of(
                        MavenCoordinate.zero("zero-data"),
                        MavenCoordinate.zero("zero-data-postgresql")),
                StandardRuntimeCapabilityModel.instance().artifactsForProviders(
                        List.of(StandardRuntimeCapabilityModel.PRODUCTION_POSTGRESQL_DATA)));
        assertEquals(
                List.of(
                        MavenCoordinate.zero("zero-data"),
                        MavenCoordinate.zero("zero-data-mongo")),
                StandardRuntimeCapabilityModel.instance().artifactsForProviders(
                        List.of(StandardRuntimeCapabilityModel.PRODUCTION_MONGO_DATA)));
        assertEquals(
                List.of(
                        MavenCoordinate.zero("zero-cache"),
                        MavenCoordinate.zero("zero-data"),
                        MavenCoordinate.zero("zero-data-redis")),
                StandardRuntimeCapabilityModel.instance().artifactsForProviders(List.of(
                        StandardRuntimeCapabilityModel.PRODUCTION_REDIS_CACHE,
                        StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA,
                        StandardRuntimeCapabilityModel.PRODUCTION_REDIS_RESOURCE)));
    }

    private static void assertKey(
            final String expectedId,
            final Class<?> expectedType,
            final BindingCardinality expectedCardinality,
            final group.zn.zero.runtime.api.BindingKey<?> key) {
        assertEquals(expectedId, key.id());
        assertEquals(expectedType, key.type());
        assertEquals(expectedCardinality, key.cardinality());
    }
}
