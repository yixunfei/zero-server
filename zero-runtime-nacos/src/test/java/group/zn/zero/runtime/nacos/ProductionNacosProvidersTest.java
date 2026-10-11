package group.zn.zero.runtime.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.runtime.production.ProductionConfigResolver;
import group.zn.zero.runtime.production.ProductionStartupBudget;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证 Nacos 配置解析边界，不暴露运行时包装实现。 */
class ProductionNacosProvidersTest {
    /** 原生请求超时取自独立运行期预算，不受启动窗口影响。 */
    @Test
    void requestTimeoutUsesRuntimeBudgetBeforeProviderCreation() {
        MapZeroConfig config = new MapZeroConfig(Map.of(
                NacosDiscoveryConfigKeys.DISCOVERY_MODE, NacosDiscoveryConfigKeys.MODE_NACOS,
                NacosDiscoveryConfigKeys.SERVER_ADDR, "127.0.0.1:8848",
                NacosDiscoveryConfigKeys.NAMESPACE, "budget-test",
                NacosDiscoveryConfigKeys.DEFAULT_GROUP, "TEST",
                NacosDiscoveryConfigKeys.DEFAULT_CLUSTER, "TEST",
                "zero.adapter.nacos.operation-timeout-millis", "2400"));
        var resolution = ProductionNacosProviders.resolve(
                new ProductionConfigResolver(config, key -> null, key -> null),
                new ProductionStartupBudget(Duration.ofSeconds(5), Duration.ofMillis(1250)),
                config, key -> null, key -> null);
        var timeout = resolution.discoveryProvider().descriptor().configSchema().keys().stream()
                .filter(key -> key.logicalName().equals(NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS))
                .findFirst().orElseThrow();
        assertEquals(2400, timeout.defaultValue().orElseThrow());
    }
}
