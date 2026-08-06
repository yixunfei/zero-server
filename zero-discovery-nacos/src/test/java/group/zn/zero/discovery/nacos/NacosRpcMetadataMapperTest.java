package group.zn.zero.discovery.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.discovery.RpcDiscoveryMetadata;
import group.zn.zero.rpc.discovery.RpcServiceInstance;
import group.zn.zero.rpc.discovery.RpcServiceQuery;
import group.zn.zero.rpc.discovery.RpcServiceSelection;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Nacos RPC metadata 映射测试。
 *
 * @author zn
 */
class NacosRpcMetadataMapperTest {

    /**
     * 验证 RPC metadata 可以从 Nacos 服务实例转换为中立 RPC 服务实例。
     */
    @Test
    void mapperShouldConvertNacosInstanceToRpcInstance() {
        ServiceInstance nacosInstance = new ServiceInstance(
                "player.remote",
                "player-1",
                "127.0.0.1",
                6200,
                ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME,
                true,
                true,
                true,
                100.0D,
                1.0D,
                NacosRpcMetadataMapper.rpcProviderMetadata(
                        "player.remote",
                        1,
                        "kafka",
                        "zero.rpc.player",
                        "player-group",
                        "player-1",
                        "1",
                        "zone-a",
                        Map.of("role", "provider")));

        RpcServiceInstance rpcInstance = NacosRpcMetadataMapper.toRpcServiceInstance(nacosInstance);

        assertEquals("player.remote", rpcInstance.serviceName());
        assertEquals(1, rpcInstance.serviceVersion());
        assertEquals("kafka", rpcInstance.transport());
        assertEquals("zero.rpc.player", rpcInstance.requestTopic());
        assertEquals("player-group", rpcInstance.consumerGroup());
        assertEquals("zone-a", rpcInstance.zone());
        assertEquals("provider", rpcInstance.attributes().get("role"));
    }

    /**
     * 验证缺失 RPC 版本 metadata 时会绑定服务发现配置错误。
     */
    @Test
    void mapperShouldRejectMissingRpcVersion() {
        ServiceInstance nacosInstance = new ServiceInstance(
                "player.remote",
                "player-1",
                "127.0.0.1",
                6200,
                true,
                Map.of(RpcDiscoveryMetadata.TRANSPORT, "kafka"));

        ZeroException exception = assertThrows(
                ZeroException.class,
                () -> NacosRpcMetadataMapper.toRpcServiceInstance(nacosInstance));

        assertEquals(DiscoveryErrorCode.DISCOVERY_CONFIGURATION_ERROR, exception.errorCode());
    }

    /**
     * 验证 ServiceDiscovery-backed resolver 可以基于本地注册表选择 RPC 实例。
     */
    @Test
    void serviceDiscoveryResolverShouldResolveRpcInstance() {
        InMemoryServiceDiscovery discovery = new InMemoryServiceDiscovery();
        discovery.start();
        discovery.register(new ServiceInstance(
                "player.remote",
                "player-1",
                "127.0.0.1",
                6200,
                true,
                NacosRpcMetadataMapper.rpcProviderMetadata(
                        "player.remote",
                        1,
                        "kafka",
                        "zero.rpc.player",
                        "player-group",
                        "player-1",
                        "1",
                        "zone-a",
                        Map.of())));
        ServiceDiscoveryRpcServiceResolver resolver = new ServiceDiscoveryRpcServiceResolver(discovery);

        RpcServiceSelection selection = resolver.resolve(RpcServiceQuery.of("player.remote", 1)
                .withTransport("kafka"));

        assertEquals("player-1", selection.instance().instanceId());
        assertEquals("zero.rpc.player", selection.instance().requestTopic());
    }

    /**
     * 验证无匹配实例时 resolver 绑定 RPC 服务不存在错误码。
     */
    @Test
    void serviceDiscoveryResolverShouldFailWhenNoInstanceMatches() {
        InMemoryServiceDiscovery discovery = new InMemoryServiceDiscovery();
        discovery.start();
        ServiceDiscoveryRpcServiceResolver resolver = new ServiceDiscoveryRpcServiceResolver(discovery);

        ZeroException exception = assertThrows(ZeroException.class, () -> resolver.resolve(
                RpcServiceQuery.of("player.remote", 1).withTransport("kafka")));

        assertEquals(RpcErrorCode.SERVICE_NOT_FOUND, exception.errorCode());
    }
}
