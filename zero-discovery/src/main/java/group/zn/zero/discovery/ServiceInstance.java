package group.zn.zero.discovery;

import java.util.Map;
import java.util.Objects;

/**
 * 服务实例。
 *
 * @param serviceName 服务名称。
 * @param instanceId 实例标识。
 * @param host 主机。
 * @param port 端口。
 * @param groupName 服务分组。
 * @param clusterName 集群名称。
 * @param healthy 是否健康。
 * @param enabled 是否启用。
 * @param ephemeral 是否临时实例。
 * @param weight 负载权重。
 * @param loadFactor 负载扩展因子。
 * @param metadata 元数据。
 * @author zn
 */
public record ServiceInstance(
        String serviceName,
        String instanceId,
        String host,
        int port,
        String groupName,
        String clusterName,
        boolean healthy,
        boolean enabled,
        boolean ephemeral,
        double weight,
        double loadFactor,
        Map<String, String> metadata) {

    /**
     * 创建服务实例。
     *
     * @param serviceName 服务名称；不可为空。
     * @param instanceId 实例标识；不可为空。
     * @param host 主机；不可为空。
     * @param port 端口。
     * @param healthy 是否健康。
     * @param metadata 元数据；不可为空。
     * @throws NullPointerException 当元数据为空时抛出。
     * @throws IllegalArgumentException 当标准文本字段为空白、端口、权重或负载因子非法时抛出。
     */
    public ServiceInstance(
            final String serviceName,
            final String instanceId,
            final String host,
            final int port,
            final boolean healthy,
            final Map<String, String> metadata) {
        this(
                serviceName,
                instanceId,
                host,
                port,
                ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                ServiceDiscoveryConstants.DEFAULT_CLUSTER_NAME,
                healthy,
                true,
                true,
                1.0D,
                1.0D,
                metadata);
    }

    /**
     * 创建服务实例。
     *
     * @throws NullPointerException 当元数据为空时抛出。
     * @throws IllegalArgumentException 当标准文本字段为空白、端口、权重或负载因子非法时抛出。
     */
    public ServiceInstance {
        requireText(serviceName, "serviceName");
        requireText(instanceId, "instanceId");
        requireText(host, "host");
        requireText(groupName, "groupName");
        requireText(clusterName, "clusterName");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (weight < 0.0D) {
            throw new IllegalArgumentException("weight must be greater than or equal to 0");
        }
        if (loadFactor < 0.0D) {
            throw new IllegalArgumentException("loadFactor must be greater than or equal to 0");
        }
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    /**
     * 返回更新健康状态后的实例。
     *
     * @param healthy 是否健康。
     * @return 新服务实例；不可为空；线程安全。
     */
    public ServiceInstance withHealthy(final boolean healthy) {
        return new ServiceInstance(
                serviceName,
                instanceId,
                host,
                port,
                groupName,
                clusterName,
                healthy,
                enabled,
                ephemeral,
                weight,
                loadFactor,
                metadata);
    }

    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
