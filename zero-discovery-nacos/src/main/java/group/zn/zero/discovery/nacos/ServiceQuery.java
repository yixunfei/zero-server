package group.zn.zero.discovery.nacos;

import java.util.List;
import java.util.Objects;

/**
 * 服务发现查询条件。
 *
 * @param serviceName 服务名称。
 * @param groupName 服务分组。
 * @param clusters 集群过滤条件。
 * @param healthyOnly 是否只返回健康实例。
 * @param subscribe 查询时是否允许实现建立订阅缓存。
 * @author zn
 */
public record ServiceQuery(
        String serviceName,
        String groupName,
        List<String> clusters,
        boolean healthyOnly,
        boolean subscribe) {

    /**
     * 创建查询条件。
     *
     * @throws NullPointerException 当集群列表为空时抛出。
     * @throws IllegalArgumentException 当服务名称、分组或集群名称为空白时抛出。
     */
    public ServiceQuery {
        requireText(serviceName, "serviceName");
        requireText(groupName, "groupName");
        clusters = List.copyOf(Objects.requireNonNull(clusters, "clusters"));
        for (String cluster : clusters) {
            requireText(cluster, "cluster");
        }
    }

    /**
     * 创建默认查询条件。
     *
     * @param serviceName 服务名称；不可为空。
     * @return 查询条件；不可为空；线程安全。
     */
    public static ServiceQuery of(final String serviceName) {
        return new ServiceQuery(
                serviceName,
                ServiceDiscoveryConstants.DEFAULT_GROUP_NAME,
                List.of(),
                false,
                false);
    }

    /**
     * 返回只查询健康实例的新条件。
     *
     * @param healthyOnly 是否只返回健康实例。
     * @return 查询条件；不可为空；线程安全。
     */
    public ServiceQuery withHealthyOnly(final boolean healthyOnly) {
        return new ServiceQuery(serviceName, groupName, clusters, healthyOnly, subscribe);
    }

    /**
     * 返回允许或禁止订阅缓存的新条件。
     *
     * @param subscribe 是否允许订阅缓存。
     * @return 查询条件；不可为空；线程安全。
     */
    public ServiceQuery withSubscribe(final boolean subscribe) {
        return new ServiceQuery(serviceName, groupName, clusters, healthyOnly, subscribe);
    }

    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
