package group.zn.zero.discovery.nacos.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.config.ZeroConfigLoader;
import group.zn.zero.discovery.InMemoryServiceDiscovery;
import group.zn.zero.discovery.nacos.NacosDiscoveryAdapter;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.discovery.nacos.NacosDiscoveryFactory;
import group.zn.zero.discovery.ServiceDiscovery;
import group.zn.zero.discovery.ServiceInstance;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Nacos 服务发现模块使用示例。
 *
 * <p>该示例展示外部项目如何通过标准 properties 配置和 `NacosDiscoveryFactory` 引用模块。
 * 默认示例不连接真实 Nacos，避免普通单元测试依赖外部组件。
 *
 * @author zn
 */
class NacosDiscoveryModuleDemoTest {

    /**
     * 验证外部 properties 可以创建本地服务发现策略并完成基础注册查询。
     *
     * @throws Exception 配置资源解析或服务发现生命周期失败时抛出。
     */
    @Test
    void localDiscoveryShouldUseExternalPropertiesConfig() throws Exception {
        ZeroConfig config = loadConfig("/nacos-demo/zero-server-local.properties");
        ServiceDiscovery discovery = NacosDiscoveryFactory.fromConfig(config);

        assertTrue(discovery instanceof InMemoryServiceDiscovery);
        discovery.start();
        try {
            discovery.register(new ServiceInstance(
                    "demo-service",
                    "demo-1",
                    "127.0.0.1",
                    6200,
                    true,
                    Map.of("source", "demo")));

            assertEquals("demo", discovery.lookup("demo-service").getFirst().metadata().get("source"));
        } finally {
            discovery.stop();
        }
    }

    /**
     * 验证外部 properties 可以创建 Nacos Adapter，但 demo 不启动真实连接。
     *
     * @throws Exception 配置资源解析失败时抛出。
     */
    @Test
    void nacosDiscoveryShouldBeCreatedFromExternalPropertiesConfig() throws Exception {
        ZeroConfig config = loadConfig("/nacos-demo/zero-server-nacos.properties");
        ServiceDiscovery discovery = NacosDiscoveryFactory.fromConfig(config);

        assertTrue(discovery instanceof NacosDiscoveryAdapter);
        NacosDiscoveryAdapter adapter = (NacosDiscoveryAdapter) discovery;
        assertEquals(NacosDiscoveryConfigKeys.MODE_NACOS, config.require(NacosDiscoveryConfigKeys.DISCOVERY_MODE));
        assertEquals("public", adapter.settings().namespace());
    }

    private ZeroConfig loadConfig(final String resourceName) throws URISyntaxException {
        URL url = NacosDiscoveryModuleDemoTest.class.getResource(resourceName);
        assertNotNull(url, "missing demo config resource: " + resourceName);
        return ZeroConfigLoader.fromPropertiesFile(Path.of(url.toURI()));
    }
}
