package group.zn.zero.rpc.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * RPC 服务实例 round-robin resolver 测试。
 *
 * @author zn
 */
class RoundRobinRpcServiceResolverTest {

    /**
     * 验证 resolver 只选择健康、启用且权重大于 0 的匹配实例，并按 round-robin 轮转。
     */
    @Test
    void resolverShouldSelectHealthyMatchingInstancesRoundRobin() {
        RoundRobinRpcServiceResolver resolver = new RoundRobinRpcServiceResolver(List.of(
                instance("player-2", true, true, 100.0D),
                instance("player-disabled", true, false, 100.0D),
                instance("player-zero-weight", true, true, 0.0D),
                instance("player-1", true, true, 100.0D),
                new RpcServiceInstance(
                        "scene.remote",
                        1,
                        "scene-1",
                        "kafka",
                        "zero.rpc.scene",
                        "scene-group",
                        "127.0.0.1",
                        6201,
                        RpcDiscoveryMetadata.DEFAULT_GROUP_NAME,
                        RpcDiscoveryMetadata.DEFAULT_CLUSTER_NAME,
                        RpcDiscoveryMetadata.DEFAULT_ZONE,
                        true,
                        true,
                        100.0D,
                        Map.of())));
        RpcServiceQuery query = RpcServiceQuery.of("player.remote", 1).withTransport("kafka");

        RpcServiceSelection first = resolver.resolve(query);
        RpcServiceSelection second = resolver.resolve(query);
        RpcServiceSelection third = resolver.resolve(query);

        assertEquals("player-1", first.instance().instanceId());
        assertEquals("player-2", second.instance().instanceId());
        assertEquals("player-1", third.instance().instanceId());
        assertEquals(2, first.candidateCount());
    }

    /**
     * 验证无匹配实例时绑定 RPC 服务不存在错误码。
     */
    @Test
    void resolverShouldFailWhenNoInstanceMatches() {
        RoundRobinRpcServiceResolver resolver = new RoundRobinRpcServiceResolver(List.of(instance(
                "player-1",
                false,
                true,
                100.0D)));
        RpcServiceQuery query = RpcServiceQuery.of("player.remote", 1).withTransport("kafka");

        ZeroException exception = assertThrows(ZeroException.class, () -> resolver.resolve(query));

        assertEquals(RpcErrorCode.SERVICE_NOT_FOUND, exception.errorCode());
    }

    /**
     * 验证替换单个服务快照不会清除其他服务实例。
     */
    @Test
    void replaceSnapshotShouldKeepOtherServices() {
        RoundRobinRpcServiceResolver resolver = new RoundRobinRpcServiceResolver(List.of(
                instance("player-old", true, true, 100.0D),
                new RpcServiceInstance(
                        "scene.remote",
                        1,
                        "scene-1",
                        "kafka",
                        "zero.rpc.scene",
                        "scene-group",
                        "127.0.0.1",
                        6201,
                        RpcDiscoveryMetadata.DEFAULT_GROUP_NAME,
                        RpcDiscoveryMetadata.DEFAULT_CLUSTER_NAME,
                        RpcDiscoveryMetadata.DEFAULT_ZONE,
                        true,
                        true,
                        100.0D,
                        Map.of())));

        resolver.replaceSnapshot(RpcServiceSnapshot.now(
                RpcServiceQuery.of("player.remote", 1).withTransport("kafka"),
                List.of(instance("player-new", true, true, 100.0D))));

        assertEquals("player-new", resolver.resolve(
                RpcServiceQuery.of("player.remote", 1).withTransport("kafka")).instance().instanceId());
        assertEquals("scene-1", resolver.resolve(
                RpcServiceQuery.of("scene.remote", 1).withTransport("kafka")).instance().instanceId());
    }

    private RpcServiceInstance instance(
            final String instanceId,
            final boolean healthy,
            final boolean enabled,
            final double weight) {
        return new RpcServiceInstance(
                "player.remote",
                1,
                instanceId,
                "kafka",
                "zero.rpc.player",
                "player-group",
                "127.0.0.1",
                6200,
                RpcDiscoveryMetadata.DEFAULT_GROUP_NAME,
                RpcDiscoveryMetadata.DEFAULT_CLUSTER_NAME,
                RpcDiscoveryMetadata.DEFAULT_ZONE,
                healthy,
                enabled,
                weight,
                Map.of());
    }
}
