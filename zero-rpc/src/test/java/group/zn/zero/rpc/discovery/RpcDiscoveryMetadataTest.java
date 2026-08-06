package group.zn.zero.rpc.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * RPC 服务发现 metadata 测试。
 *
 * @author zn
 */
class RpcDiscoveryMetadataTest {

    /**
     * 验证 provider metadata 只写入非空字段。
     */
    @Test
    void providerMetadataShouldKeepOnlyPresentValues() {
        Map<String, String> metadata = RpcDiscoveryMetadata.providerMetadata(
                "logic-service",
                "1",
                "zero.rpc.logic-service",
                "",
                "logic-1",
                "kafka");

        assertEquals("logic-service", metadata.get(RpcDiscoveryMetadata.SERVICE_NAME));
        assertEquals("1", metadata.get(RpcDiscoveryMetadata.VERSION));
        assertEquals("zero.rpc.logic-service", metadata.get(RpcDiscoveryMetadata.TOPIC));
        assertEquals("logic-1", metadata.get(RpcDiscoveryMetadata.INSTANCE_ID));
        assertEquals("kafka", metadata.get(RpcDiscoveryMetadata.PROTOCOL));
        assertFalse(metadata.containsKey(RpcDiscoveryMetadata.GROUP));
    }

    /**
     * 验证标准 provider metadata 同时写入新旧兼容 key。
     */
    @Test
    void providerMetadataShouldWriteStandardAndCompatibilityKeys() {
        Map<String, String> metadata = RpcDiscoveryMetadata.providerMetadata(
                "player.remote",
                1,
                "kafka",
                "zero.rpc.player",
                "player-group",
                "player-1",
                "1",
                "zone-a",
                Map.of("custom", "value"));

        assertEquals("player.remote", metadata.get(RpcDiscoveryMetadata.SERVICE_NAME));
        assertEquals("1", metadata.get(RpcDiscoveryMetadata.SERVICE_VERSION));
        assertEquals("1", metadata.get(RpcDiscoveryMetadata.VERSION));
        assertEquals("kafka", metadata.get(RpcDiscoveryMetadata.TRANSPORT));
        assertEquals("kafka", metadata.get(RpcDiscoveryMetadata.PROTOCOL));
        assertEquals("zero.rpc.player", metadata.get(RpcDiscoveryMetadata.REQUEST_TOPIC));
        assertEquals("zero.rpc.player", metadata.get(RpcDiscoveryMetadata.TOPIC));
        assertEquals("player-group", metadata.get(RpcDiscoveryMetadata.CONSUMER_GROUP));
        assertEquals("player-group", metadata.get(RpcDiscoveryMetadata.GROUP));
        assertEquals("zone-a", metadata.get(RpcDiscoveryMetadata.ZONE));
        assertEquals("value", metadata.get("custom"));
    }
}
