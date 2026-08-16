package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.discovery.nacos.NacosDiscoveryAdapter;
import group.zn.zero.discovery.nacos.ServiceDiscoveryRpcServiceResolver;
import group.zn.zero.starter.ZeroRuntimeConfigKeys;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Nacos provider family 的中立组件图契约测试。 */
class ProductionNacosProvidersTest {

    /** 验证 discovery 与 resolver 通过显式能力依赖完成创建和绑定。 */
    @Test
    void selectedProvidersShouldBindNeutralDiscoveryAndResolverCapabilities() {
        MapZeroConfig config = new MapZeroConfig(Map.of(
                ZeroRuntimeConfigKeys.ZERO_MODE, ZeroProductionRuntimeConfigKeys.MODE_EXTERNAL_TEST,
                ZeroRuntimeConfigKeys.ZERO_NAME, "nacos-provider-test",
                NacosDiscoveryConfigKeys.DISCOVERY_MODE, NacosDiscoveryConfigKeys.MODE_NACOS,
                NacosDiscoveryConfigKeys.SERVER_ADDR, "127.0.0.1:8848",
                NacosDiscoveryConfigKeys.NAMESPACE, "provider-test",
                NacosDiscoveryConfigKeys.DEFAULT_GROUP, "PROVIDER_TEST",
                NacosDiscoveryConfigKeys.DEFAULT_CLUSTER, "PROVIDER_TEST"));
        ZeroProductionRuntime runtime = ZeroProductionRuntimeFactory.externalTestBuilder(config)
                .configSourceLookups(key -> null, key -> null)
                .build();
        try {
            assertInstanceOf(
                    NacosDiscoveryAdapter.class,
                    runtime.require(ProductionRuntimeCapabilities.SERVICE_DISCOVERY));
            assertInstanceOf(
                    ServiceDiscoveryRpcServiceResolver.class,
                    runtime.require(ProductionRuntimeCapabilities.RPC_SERVICE_RESOLVER));
        } finally {
            runtime.close();
        }
    }
}
