package group.zn.zero.ownership;

import java.util.Optional;

/** 可选 owner 路由端口；路由记录必须遵守 lease 的 epoch fencing。 */
public interface OwnershipRouter {
    /** 查询当前可用路由；不可用或过期时返回空。 */
    Optional<OwnershipRoute> resolve(OwnershipResource resource);

    /** 发布与指定 lease 绑定的路由；旧 epoch 或旧 owner 必须拒绝。 */
    OwnershipRoute publish(OwnershipLease lease, String endpoint);

    /** 仅撤销仍匹配指定 lease 的路由，避免删除新 owner 路由。 */
    boolean withdraw(OwnershipLease lease);
}
