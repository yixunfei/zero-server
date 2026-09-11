package group.zn.zero.discovery.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.discovery.ServiceDiscoveryConstants;
import group.zn.zero.discovery.ServiceEvent;
import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.discovery.ServiceQuery;
import group.zn.zero.discovery.ServiceSubscription;
import group.zn.zero.rpc.discovery.NacosRpcMetadataMapper;
import group.zn.zero.rpc.discovery.RpcServiceQuery;
import group.zn.zero.rpc.discovery.RpcServiceSelection;
import group.zn.zero.rpc.discovery.ServiceDiscoveryRpcServiceResolver;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Nacos 服务发现真实外部依赖测试。
 *
 * @author zn
 */
class NacosDiscoveryAdapterExternalIT {

    /**
     * external-tests profile 标记。
     */
    private static final String EXTERNAL_TESTS_ENABLED = "zero.external.tests";

    /**
     * Nacos 地址系统属性。
     */
    private static final String NACOS_SERVER_ADDR_PROPERTY = "zero.nacos.serverAddr";

    /**
     * Nacos 地址环境变量。
     */
    private static final String NACOS_SERVER_ADDR_ENV = "ZERO_NACOS_SERVER_ADDR";

    /**
     * 等待超时时间。
     */
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 验证真实 Nacos 可以完成注册、查询、订阅最终快照和注销。
     *
     * @throws Exception 等待中断或 Nacos 操作失败时抛出。
     */
    @Test
    void adapterShouldRegisterLookupSubscribeAndUnregisterAgainstNacos() throws Exception {
        assertTrue(Boolean.getBoolean(EXTERNAL_TESTS_ENABLED), "external-tests profile must set zero.external.tests=true");
        String serverAddr = nacosServerAddr();
        String serviceName = "zero-s2c04-" + System.nanoTime();
        String instanceId = serviceName + "-instance";
        ServiceQuery query = new ServiceQuery(
                serviceName,
                ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                List.of(ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME),
                false,
                true);
        NacosDiscoveryAdapter adapter = new NacosDiscoveryAdapter(new NacosDiscoverySettings(
                serverAddr,
                NacosDiscoverySettings.DEFAULT_NAMESPACE,
                "",
                "",
                "",
                "",
                ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME,
                5_000,
                false,
                NacosHealthUpdateMode.REREGISTER,
                Map.of()));
        List<ServiceEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch snapshotArrived = new CountDownLatch(1);
        ServiceSubscription subscription = null;

        try {
            adapter.start();
            subscription = adapter.subscribe(query, event -> {
                events.add(event);
                if (event.instances().stream().anyMatch(instance -> instance.instanceId().equals(instanceId))) {
                    snapshotArrived.countDown();
                }
            });
            adapter.register(new ServiceInstance(
                    serviceName,
                    instanceId,
                    "127.0.0.1",
                    6200,
                    ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                    ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME,
                    true,
                    true,
                    true,
                    1.0D,
                    1.0D,
                    NacosRpcMetadataMapper.rpcProviderMetadata(
                            serviceName,
                            1,
                            "kafka",
                            "zero.rpc.external." + serviceName,
                            "provider-group-" + serviceName,
                            instanceId,
                            "1",
                            "external-test",
                            Map.of("stage", "s2c04"))));

            await(() -> adapter.lookup(query).stream().anyMatch(instance -> instance.instanceId().equals(instanceId)));
            assertTrue(snapshotArrived.await(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS), "Nacos snapshot event not received");
            ServiceInstance found = adapter.lookup(query).stream()
                    .filter(instance -> instance.instanceId().equals(instanceId))
                    .findFirst()
                    .orElseThrow();
            RpcServiceSelection selection = new ServiceDiscoveryRpcServiceResolver(adapter).resolve(
                    RpcServiceQuery.of(serviceName, 1).withTransport("kafka"));

            assertEquals("127.0.0.1", found.host());
            assertEquals("s2c04", found.metadata().get("stage"));
            assertEquals(instanceId, selection.instance().instanceId());
            assertEquals("zero.rpc.external." + serviceName, selection.instance().requestTopic());
            assertFalse(events.isEmpty());

            adapter.unregister(serviceName, ServiceDiscoveryConstants.DEFAULT_GROUP_NAME, instanceId);
            await(() -> adapter.lookup(query).stream().noneMatch(instance -> instance.instanceId().equals(instanceId)));
        } finally {
            if (subscription != null) {
                subscription.close();
            }
            if (adapter.running()) {
                adapter.stop();
            }
        }
    }

    private String nacosServerAddr() {
        String serverAddr = Optional.ofNullable(System.getProperty(NACOS_SERVER_ADDR_PROPERTY))
                .or(() -> Optional.ofNullable(System.getenv(NACOS_SERVER_ADDR_ENV)))
                .filter(value -> !value.isBlank())
                .orElse(null);
        assertNotNull(serverAddr, "Nacos server address must be set by zero.nacos.serverAddr or ZERO_NACOS_SERVER_ADDR");
        return serverAddr;
    }

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
}
