package group.zn.zero.rpc.discovery;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RPC 服务发现 metadata 工具。
 *
 * <p>该工具只定义 RPC provider 写入服务发现实例的轻量 metadata，不改变 `RpcTransport`
 * 请求语义，也不让 `zero-rpc` 依赖任何具体服务发现实现。
 *
 * @author zn
 */
public final class RpcDiscoveryMetadata {

    /**
     * RPC 服务名元数据键。
     */
    public static final String SERVICE_NAME = "zero.rpc.serviceName";

    /**
     * RPC 服务版本元数据键。
     */
    public static final String VERSION = "zero.rpc.version";

    /**
     * RPC 服务版本标准元数据键。
     */
    public static final String SERVICE_VERSION = "zero.rpc.serviceVersion";

    /**
     * RPC topic 元数据键。
     */
    public static final String TOPIC = "zero.rpc.topic";

    /**
     * RPC request topic 标准元数据键。
     */
    public static final String REQUEST_TOPIC = "zero.rpc.requestTopic";

    /**
     * RPC consumer group 元数据键。
     */
    public static final String GROUP = "zero.rpc.group";

    /**
     * RPC consumer group 标准元数据键。
     */
    public static final String CONSUMER_GROUP = "zero.rpc.consumerGroup";

    /**
     * RPC 实例标识元数据键。
     */
    public static final String INSTANCE_ID = "zero.rpc.instanceId";

    /**
     * RPC 协议元数据键。
     */
    public static final String PROTOCOL = "zero.rpc.protocol";

    /**
     * RPC transport 标准元数据键。
     */
    public static final String TRANSPORT = "zero.rpc.transport";

    /**
     * RPC 协议版本元数据键。
     */
    public static final String PROTOCOL_VERSION = "zero.rpc.protocolVersion";

    /**
     * RPC 所在区服或可用区元数据键。
     */
    public static final String ZONE = "zero.rpc.zone";

    /**
     * 默认服务发现分组。
     */
    public static final String DEFAULT_GROUP_NAME = "DEFAULT_GROUP";

    /**
     * 默认集群。
     */
    public static final String DEFAULT_CLUSTER_NAME = "DEFAULT";

    /**
     * 默认区服。
     */
    public static final String DEFAULT_ZONE = "default";

    private RpcDiscoveryMetadata() {
    }

    /**
     * 创建 RPC provider 服务发现 metadata。
     *
     * @param serviceName RPC 服务名；可为空。
     * @param version RPC 服务版本；可为空。
     * @param topic RPC topic；可为空。
     * @param group RPC consumer group；可为空。
     * @param instanceId RPC 实例标识；可为空。
     * @param protocol RPC 协议；可为空。
     * @return 不可变、无序、可能为空、线程安全的 metadata。
     */
    public static Map<String, String> providerMetadata(
            final String serviceName,
            final String version,
            final String topic,
            final String group,
            final String instanceId,
            final String protocol) {
        Map<String, String> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, SERVICE_NAME, serviceName);
        putIfPresent(metadata, VERSION, version);
        putIfPresent(metadata, SERVICE_VERSION, version);
        putIfPresent(metadata, TOPIC, topic);
        putIfPresent(metadata, REQUEST_TOPIC, topic);
        putIfPresent(metadata, GROUP, group);
        putIfPresent(metadata, CONSUMER_GROUP, group);
        putIfPresent(metadata, INSTANCE_ID, instanceId);
        putIfPresent(metadata, PROTOCOL, protocol);
        putIfPresent(metadata, TRANSPORT, protocol);
        return Map.copyOf(metadata);
    }

    /**
     * 创建标准 RPC provider 服务发现 metadata。
     *
     * @param serviceName RPC 服务名；不可为空。
     * @param serviceVersion RPC 服务版本；必须为正数。
     * @param transport RPC 传输名称；不可为空。
     * @param requestTopic request topic；可为空。
     * @param consumerGroup consumer group；可为空。
     * @param instanceId RPC 实例标识；可为空。
     * @param protocolVersion RPC 协议版本；可为空。
     * @param zone 区服或可用区；可为空。
     * @param attributes 扩展属性；不可为空；不可变、无序、可能为空、线程安全。
     * @return 不可变、无序、可能为空、线程安全的 metadata。
     * @throws NullPointerException 当扩展属性为空时抛出。
     * @throws IllegalArgumentException 当服务名、版本或传输名称非法时抛出。
     */
    public static Map<String, String> providerMetadata(
            final String serviceName,
            final int serviceVersion,
            final String transport,
            final String requestTopic,
            final String consumerGroup,
            final String instanceId,
            final String protocolVersion,
            final String zone,
            final Map<String, String> attributes) {
        requireText(serviceName, "serviceName");
        requireText(transport, "transport");
        if (serviceVersion <= 0) {
            throw new IllegalArgumentException("serviceVersion must be positive");
        }
        Map<String, String> metadata = new LinkedHashMap<>(Map.copyOf(attributes));
        metadata.put(SERVICE_NAME, serviceName);
        metadata.put(SERVICE_VERSION, Integer.toString(serviceVersion));
        metadata.put(VERSION, Integer.toString(serviceVersion));
        metadata.put(TRANSPORT, transport);
        metadata.put(PROTOCOL, transport);
        putIfPresent(metadata, REQUEST_TOPIC, requestTopic);
        putIfPresent(metadata, TOPIC, requestTopic);
        putIfPresent(metadata, CONSUMER_GROUP, consumerGroup);
        putIfPresent(metadata, GROUP, consumerGroup);
        putIfPresent(metadata, INSTANCE_ID, instanceId);
        putIfPresent(metadata, PROTOCOL_VERSION, protocolVersion);
        putIfPresent(metadata, ZONE, zone);
        return Map.copyOf(metadata);
    }

    private static void putIfPresent(final Map<String, String> metadata, final String key, final String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
