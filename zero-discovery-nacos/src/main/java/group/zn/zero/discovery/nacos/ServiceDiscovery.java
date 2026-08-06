package group.zn.zero.discovery.nacos;

import group.zn.zero.core.lifecycle.Lifecycle;
import java.util.List;
import java.util.Map;

/**
 * 服务发现抽象。
 *
 * @author zn
 */
public interface ServiceDiscovery extends Lifecycle {

    /**
     * 注册服务实例。
     *
     * @param instance 服务实例；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 注册失败时抛出，必须绑定 ErrorCode。
     */
    void register(ServiceInstance instance);

    /**
     * 注销服务实例。
     *
     * @param serviceName 服务名称；不可为空。
     * @param instanceId 实例标识；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 注销失败时抛出，必须绑定 ErrorCode。
     */
    void unregister(String serviceName, String instanceId);

    /**
     * 注销服务实例。
     *
     * @param serviceName 服务名称；不可为空。
     * @param groupName 服务分组；不可为空。
     * @param instanceId 实例标识；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 注销失败时抛出，必须绑定 ErrorCode。
     */
    void unregister(String serviceName, String groupName, String instanceId);

    /**
     * 更新服务实例健康状态。
     *
     * @param serviceName 服务名称；不可为空。
     * @param instanceId 实例标识；不可为空。
     * @param healthy 是否健康。
     * @throws group.zn.zero.core.error.ZeroException 更新失败时抛出，必须绑定 ErrorCode。
     */
    void updateHealth(String serviceName, String instanceId, boolean healthy);

    /**
     * 更新服务实例健康状态。
     *
     * @param serviceName 服务名称；不可为空。
     * @param groupName 服务分组；不可为空。
     * @param instanceId 实例标识；不可为空。
     * @param healthy 是否健康。
     * @throws group.zn.zero.core.error.ZeroException 更新失败时抛出，必须绑定 ErrorCode。
     */
    void updateHealth(String serviceName, String groupName, String instanceId, boolean healthy);

    /**
     * 查询服务实例。
     *
     * @param serviceName 服务名称；不可为空。
     * @return 服务实例列表；不可为空；可能为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 查询失败时抛出，必须绑定 ErrorCode。
     */
    List<ServiceInstance> lookup(String serviceName);

    /**
     * 查询服务实例。
     *
     * @param query 查询条件；不可为空。
     * @return 服务实例列表；不可为空；可能为空；返回集合不可变；是否有序由实现声明；线程安全。
     * @throws group.zn.zero.core.error.ZeroException 查询失败时抛出，必须绑定 ErrorCode。
     */
    List<ServiceInstance> lookup(ServiceQuery query);

    /**
     * 订阅服务最终实例快照变更。
     *
     * @param query 查询条件；不可为空。
     * @param listener 监听器；不可为空。
     * @return 订阅句柄；不可为空；调用 close 可取消订阅；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 订阅失败时抛出，必须绑定 ErrorCode。
     */
    ServiceSubscription subscribe(ServiceQuery query, ServiceDiscoveryListener listener);

    /**
     * 监听服务事件。
     *
     * @param listener 监听器；不可为空。
     */
    void addListener(ServiceDiscoveryListener listener);

    /**
     * 返回服务注册快照。
     *
     * @return 不可变、无序、可能为空、线程安全的注册表快照。
     */
    Map<String, List<ServiceInstance>> snapshot();
}
