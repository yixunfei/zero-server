package group.zn.zero.discovery;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 服务发现事件。
 *
 * @param type 事件类型。
 * @param serviceName 服务名称。
 * @param groupName 服务分组。
 * @param clusters 集群过滤条件。
 * @param instances 当前最终实例快照。
 * @param time 事件时间。
 * @author zn
 */
public record ServiceEvent(
        ServiceEventType type,
        String serviceName,
        String groupName,
        List<String> clusters,
        List<ServiceInstance> instances,
        Instant time) {

    /**
     * 创建服务发现事件。
     *
     * @throws NullPointerException 当事件类型、集群列表、实例列表或时间为空时抛出。
     * @throws IllegalArgumentException 当服务名称、分组或集群名称为空白时抛出。
     */
    public ServiceEvent {
        Objects.requireNonNull(type, "type");
        requireText(serviceName, "serviceName");
        requireText(groupName, "groupName");
        clusters = List.copyOf(Objects.requireNonNull(clusters, "clusters"));
        for (String cluster : clusters) {
            requireText(cluster, "cluster");
        }
        instances = List.copyOf(Objects.requireNonNull(instances, "instances"));
        Objects.requireNonNull(time, "time");
    }

    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
