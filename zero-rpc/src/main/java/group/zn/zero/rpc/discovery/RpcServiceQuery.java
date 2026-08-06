package group.zn.zero.rpc.discovery;

import java.util.Objects;

/**
 * RPC 服务发现查询条件。
 *
 * @param serviceName RPC 服务名。
 * @param serviceVersion RPC 服务版本。
 * @param transport 传输名称；为空字符串表示不限定。
 * @param groupName 服务发现分组。
 * @param clusterName 集群名称；为空字符串表示不限定。
 * @param healthyOnly 是否只选择健康实例。
 * @author zn
 */
public record RpcServiceQuery(
        String serviceName,
        int serviceVersion,
        String transport,
        String groupName,
        String clusterName,
        boolean healthyOnly) {

    /**
     * 创建 RPC 服务发现查询条件。
     *
     * @throws IllegalArgumentException 当服务名、版本、分组非法或可选文本含空白时抛出。
     */
    public RpcServiceQuery {
        serviceName = requireText(serviceName, "serviceName");
        if (serviceVersion <= 0) {
            throw new IllegalArgumentException("serviceVersion must be positive");
        }
        transport = valueOrEmpty(transport, "transport");
        groupName = defaultText(groupName, RpcDiscoveryMetadata.DEFAULT_GROUP_NAME);
        clusterName = valueOrEmpty(clusterName, "clusterName");
    }

    /**
     * 创建默认查询条件。
     *
     * @param serviceName RPC 服务名；不可为空。
     * @param serviceVersion RPC 服务版本；必须为正数。
     * @return 查询条件；不可为空；线程安全。
     */
    public static RpcServiceQuery of(final String serviceName, final int serviceVersion) {
        return new RpcServiceQuery(
                serviceName,
                serviceVersion,
                "",
                RpcDiscoveryMetadata.DEFAULT_GROUP_NAME,
                "",
                true);
    }

    /**
     * 返回限定传输名称的新查询条件。
     *
     * @param transport 传输名称；不可为空白。
     * @return 查询条件；不可为空；线程安全。
     */
    public RpcServiceQuery withTransport(final String transport) {
        return new RpcServiceQuery(serviceName, serviceVersion, transport, groupName, clusterName, healthyOnly);
    }

    /**
     * 返回限定服务发现分组的新查询条件。
     *
     * @param groupName 服务发现分组；不可为空白。
     * @return 查询条件；不可为空；线程安全。
     */
    public RpcServiceQuery withGroupName(final String groupName) {
        return new RpcServiceQuery(serviceName, serviceVersion, transport, groupName, clusterName, healthyOnly);
    }

    /**
     * 返回限定集群的新查询条件。
     *
     * @param clusterName 集群名称；可为空。
     * @return 查询条件；不可为空；线程安全。
     */
    public RpcServiceQuery withClusterName(final String clusterName) {
        return new RpcServiceQuery(serviceName, serviceVersion, transport, groupName, clusterName, healthyOnly);
    }

    /**
     * 返回调整健康过滤的新查询条件。
     *
     * @param healthyOnly 是否只选择健康实例。
     * @return 查询条件；不可为空；线程安全。
     */
    public RpcServiceQuery withHealthyOnly(final boolean healthyOnly) {
        return new RpcServiceQuery(serviceName, serviceVersion, transport, groupName, clusterName, healthyOnly);
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
