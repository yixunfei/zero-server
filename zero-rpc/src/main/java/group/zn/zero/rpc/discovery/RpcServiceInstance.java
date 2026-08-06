package group.zn.zero.rpc.discovery;

import java.util.Map;
import java.util.Objects;

/**
 * RPC 可调用服务实例。
 *
 * @param serviceName RPC 服务名。
 * @param serviceVersion RPC 服务版本。
 * @param instanceId 实例标识。
 * @param transport 传输名称。
 * @param requestTopic request topic；可为空字符串。
 * @param consumerGroup consumer group；可为空字符串。
 * @param host 实例主机。
 * @param port 实例端口。
 * @param groupName 服务发现分组。
 * @param clusterName 集群名称。
 * @param zone 区服或可用区。
 * @param healthy 是否健康。
 * @param enabled 是否启用。
 * @param weight 权重；0 表示不接流量。
 * @param attributes 扩展属性；不可为空；不可变、无序、可能为空、线程安全。
 * @author zn
 */
public record RpcServiceInstance(
        String serviceName,
        int serviceVersion,
        String instanceId,
        String transport,
        String requestTopic,
        String consumerGroup,
        String host,
        int port,
        String groupName,
        String clusterName,
        String zone,
        boolean healthy,
        boolean enabled,
        double weight,
        Map<String, String> attributes) {

    /**
     * 创建 RPC 服务实例。
     *
     * @throws NullPointerException 当扩展属性为空时抛出。
     * @throws IllegalArgumentException 当身份字段、版本、端口或权重非法时抛出。
     */
    public RpcServiceInstance {
        serviceName = requireText(serviceName, "serviceName");
        if (serviceVersion <= 0) {
            throw new IllegalArgumentException("serviceVersion must be positive");
        }
        instanceId = requireText(instanceId, "instanceId");
        transport = requireText(transport, "transport");
        requestTopic = valueOrEmpty(requestTopic, "requestTopic");
        consumerGroup = valueOrEmpty(consumerGroup, "consumerGroup");
        host = requireText(host, "host");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        groupName = defaultText(groupName, RpcDiscoveryMetadata.DEFAULT_GROUP_NAME);
        clusterName = defaultText(clusterName, RpcDiscoveryMetadata.DEFAULT_CLUSTER_NAME);
        zone = defaultText(zone, RpcDiscoveryMetadata.DEFAULT_ZONE);
        if (weight < 0.0D) {
            throw new IllegalArgumentException("weight must be greater than or equal to 0");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * 判断当前实例是否匹配查询条件。
     *
     * @param query 查询条件；不可为空。
     * @return true 表示匹配；线程安全。
     */
    public boolean matches(final RpcServiceQuery query) {
        RpcServiceQuery current = Objects.requireNonNull(query, "query");
        if (!serviceName.equals(current.serviceName()) || serviceVersion != current.serviceVersion()) {
            return false;
        }
        if (!current.transport().isBlank() && !transport.equals(current.transport())) {
            return false;
        }
        if (!groupName.equals(current.groupName())) {
            return false;
        }
        if (!current.clusterName().isBlank() && !clusterName.equals(current.clusterName())) {
            return false;
        }
        return !current.healthyOnly() || (healthy && enabled && weight > 0.0D);
    }

    private static String defaultText(final String value, final String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    private static String valueOrEmpty(final String value, final String name) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
