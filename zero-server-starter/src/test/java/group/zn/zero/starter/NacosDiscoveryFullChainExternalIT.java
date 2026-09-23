package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.config.ZeroConfigLoader;
import group.zn.zero.discovery.nacos.NacosDiscoveryAdapter;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.discovery.nacos.NacosDiscoveryFactory;
import group.zn.zero.discovery.ServiceDiscovery;
import group.zn.zero.discovery.ServiceDiscoveryConstants;
import group.zn.zero.discovery.ServiceEvent;
import group.zn.zero.discovery.ServiceEventType;
import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.discovery.ServiceQuery;
import group.zn.zero.discovery.ServiceSubscription;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.rpc.discovery.RpcDiscoveryMetadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * Nacos 服务发现跨模块完整链路真实外部测试。
 *
 * @author zn
 */
class NacosDiscoveryFullChainExternalIT {

    /**
     * external-tests profile 标记。
     */
    private static final String EXTERNAL_TESTS_ENABLED = "zero.external.tests";

    /**
     * 等待真实 Nacos 最终一致状态的超时时间。
     */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 测试用服务分组。
     */
    private static final String GROUP_NAME = ServiceDiscoveryConstants.DEFAULT_GROUP_NAME;

    /**
     * 测试用服务集群。
     */
    private static final String CLUSTER_NAME = ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME;

    /**
     * 测试用临时目录。
     */
    @TempDir
    private Path tempDir;

    /**
     * 验证外部配置、starter、Nacos 策略和 RPC metadata 可以组合成真实服务发现链路。
     *
     * @throws Exception 当临时配置、Nacos 操作或等待状态失败时抛出。
     */
    @Test
    void starterBoundaryShouldRunNacosDiscoveryFullChainWithRpcMetadata() throws Exception {
        assertTrue(Boolean.getBoolean(EXTERNAL_TESTS_ENABLED), "external-tests profile must be enabled");
        String serverAddr = nacosServerAddr();
        ZeroConfig config = ZeroConfigLoader.fromPropertiesFile(writeConfigFile(serverAddr));
        ZeroServerApplication application = new ZeroServerApplication(config, new InMemoryLogSink());
        ServiceDiscovery discovery = null;
        ServiceSubscription subscription = null;
        String serviceName = "zero-starter-s2c04-full-" + System.nanoTime();
        String instanceId = serviceName + "-rpc-provider";
        ServiceQuery query = new ServiceQuery(serviceName, GROUP_NAME, List.of(CLUSTER_NAME), false, true);
        ServiceQuery healthyQuery = query.withHealthyOnly(true);
        List<ServiceEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch snapshotArrived = new CountDownLatch(1);

        try {
            assertEquals("nacos", config.get(NacosDiscoveryConfigKeys.DISCOVERY_MODE).orElseThrow());
            assertEquals("public", config.get(NacosDiscoveryConfigKeys.NAMESPACE).orElseThrow());
            application.start();
            assertTrue(application.running());

            discovery = NacosDiscoveryFactory.fromConfig(config);
            assertInstanceOf(NacosDiscoveryAdapter.class, discovery);
            discovery.start();
            ServiceDiscovery activeDiscovery = discovery;
            subscription = activeDiscovery.subscribe(query, event -> {
                events.add(event);
                if (containsInstance(event.instances(), instanceId)) {
                    snapshotArrived.countDown();
                }
            });

            activeDiscovery.register(new ServiceInstance(
                    serviceName,
                    instanceId,
                    "127.0.0.1",
                    62104,
                    GROUP_NAME,
                    CLUSTER_NAME,
                    true,
                    true,
                    true,
                    2.0D,
                    0.5D,
                    RpcDiscoveryMetadata.providerMetadata(
                            serviceName,
                            "1.0.0",
                            "zero.rpc.full-chain.requests",
                            "zero-rpc-provider",
                            instanceId,
                            "kafka")));

            ServiceInstance found = awaitInstance(activeDiscovery, query, instanceId);
            assertTrue(snapshotArrived.await(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "Nacos snapshot event not received");
            assertRpcMetadata(found, serviceName, instanceId);
            assertEquals(2.0D, found.weight());
            assertTrue(events.stream().anyMatch(event -> event.type() == ServiceEventType.SNAPSHOT_CHANGED));

            activeDiscovery.updateHealth(serviceName, GROUP_NAME, instanceId, false);
            await(() -> activeDiscovery.lookup(query).stream()
                    .anyMatch(instance -> instance.instanceId().equals(instanceId) && !instance.healthy()));
            await(() -> activeDiscovery.lookup(healthyQuery).stream()
                    .noneMatch(instance -> instance.instanceId().equals(instanceId)));

            activeDiscovery.updateHealth(serviceName, GROUP_NAME, instanceId, true);
            await(() -> activeDiscovery.lookup(healthyQuery).stream()
                    .anyMatch(instance -> instance.instanceId().equals(instanceId) && instance.healthy()));

            activeDiscovery.unregister(serviceName, GROUP_NAME, instanceId);
            await(() -> activeDiscovery.lookup(query).stream()
                    .noneMatch(instance -> instance.instanceId().equals(instanceId)));
        } finally {
            if (subscription != null) {
                subscription.close();
            }
            if (discovery != null && discovery.running()) {
                discovery.stop();
            }
            if (application.running()) {
                application.stop();
            }
        }
    }

    /**
     * 写入测试专用外部标准配置文件。
     *
     * @param serverAddr Nacos 服务端地址；不可以为空。
     * @return 配置文件路径；不为空。
     * @throws IOException 当写入临时配置文件失败时抛出。
     */
    private Path writeConfigFile(final String serverAddr) throws IOException {
        Path configFile = tempDir.resolve("zero-nacos-full-chain.properties");
        Files.writeString(configFile, String.join(System.lineSeparator(), List.of(
                "zero.mode=external-test",
                NacosDiscoveryConfigKeys.DISCOVERY_MODE + '=' + NacosDiscoveryConfigKeys.MODE_NACOS,
                NacosDiscoveryConfigKeys.SERVER_ADDR + '=' + serverAddr,
                NacosDiscoveryConfigKeys.NAMESPACE + "=public",
                NacosDiscoveryConfigKeys.DEFAULT_GROUP + '=' + GROUP_NAME,
                NacosDiscoveryConfigKeys.DEFAULT_CLUSTER + '=' + CLUSTER_NAME,
                NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS + "=10000",
                NacosDiscoveryConfigKeys.NAMING_LOAD_CACHE_AT_START + "=false",
                NacosDiscoveryConfigKeys.HEALTH_UPDATE_MODE + "=reregister"
        )), StandardCharsets.UTF_8);
        return configFile;
    }

    /**
     * 读取 Nacos 服务端地址。
     *
     * @return Nacos 服务端地址；不为空。
     */
    private String nacosServerAddr() {
        String serverAddr = Optional.ofNullable(System.getProperty(NacosDiscoveryConfigKeys.SYSTEM_SERVER_ADDR))
                .or(() -> Optional.ofNullable(System.getenv(NacosDiscoveryConfigKeys.ENV_SERVER_ADDR)))
                .filter(value -> !value.isBlank())
                .orElse(null);
        assertNotNull(serverAddr, "Nacos server address must be set by zero.nacos.serverAddr or ZERO_NACOS_SERVER_ADDR");
        return serverAddr;
    }

    /**
     * 等待指定实例可被查询。
     *
     * @param discovery 服务发现实现；不可以为空。
     * @param query 查询条件；不可以为空。
     * @param instanceId 实例标识；不可以为空。
     * @return 查到的实例；不为空。
     * @throws InterruptedException 当等待被中断时抛出。
     */
    private ServiceInstance awaitInstance(
            final ServiceDiscovery discovery,
            final ServiceQuery query,
            final String instanceId) throws InterruptedException {
        ServiceInstance[] found = new ServiceInstance[1];
        await(() -> {
            found[0] = discovery.lookup(query).stream()
                    .filter(instance -> instance.instanceId().equals(instanceId))
                    .findFirst()
                    .orElse(null);
            return found[0] != null;
        });
        return found[0];
    }

    /**
     * 等待条件满足。
     *
     * @param condition 条件；不可以为空。
     * @throws InterruptedException 当等待被中断时抛出。
     */
    private void await(final BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200L);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within " + WAIT_TIMEOUT);
    }

    /**
     * 判断实例列表是否包含指定实例。
     *
     * @param instances 实例快照；不可以为空。
     * @param instanceId 实例标识；不可以为空。
     * @return true 表示包含；线程安全性由调用方持有的快照保证。
     */
    private boolean containsInstance(final List<ServiceInstance> instances, final String instanceId) {
        return instances.stream().anyMatch(instance -> instance.instanceId().equals(instanceId));
    }

    /**
     * 校验 RPC 服务发现 metadata。
     *
     * @param instance 服务实例；不可以为空。
     * @param serviceName 服务名；不可以为空。
     * @param instanceId 实例标识；不可以为空。
     */
    private void assertRpcMetadata(final ServiceInstance instance, final String serviceName, final String instanceId) {
        Map<String, String> metadata = instance.metadata();
        assertEquals(serviceName, metadata.get(RpcDiscoveryMetadata.SERVICE_NAME));
        assertEquals("1.0.0", metadata.get(RpcDiscoveryMetadata.VERSION));
        assertEquals("zero.rpc.full-chain.requests", metadata.get(RpcDiscoveryMetadata.TOPIC));
        assertEquals("zero-rpc-provider", metadata.get(RpcDiscoveryMetadata.GROUP));
        assertEquals(instanceId, metadata.get(RpcDiscoveryMetadata.INSTANCE_ID));
        assertEquals("kafka", metadata.get(RpcDiscoveryMetadata.PROTOCOL));
        assertFalse(metadata.isEmpty());
    }
}
