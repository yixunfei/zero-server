package group.zn.zero.ownership;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** ownership 权威端口；实现必须在提交端执行 epoch fencing。 */
public interface OwnershipStore {
    /** 获取未占用或已过期资源的租约，并分配单调递增 epoch。 */
    OwnershipLease acquire(OwnershipResource resource, ProcessIncarnation owner, Duration leaseDuration);

    /** 续约；陈旧进程或陈旧 epoch 必须拒绝。 */
    OwnershipLease renew(OwnershipLease lease, Duration leaseDuration);

    /** 释放当前租约；过期或陈旧租约不应删除新 owner。 */
    boolean release(OwnershipLease lease);

    /** 在资源已过期时接管并分配新 epoch。 */
    OwnershipLease claim(OwnershipResource resource, ProcessIncarnation owner, Duration leaseDuration);

    /** 查询当前有效租约；后端不可用时由实现抛出异常而不是返回空。 */
    Optional<OwnershipLease> current(OwnershipResource resource, Instant now);

    /**
     * 在权威端确认租约仍代表当前 owner；失败时抛出 ownership 异常。
     * 实现必须在检查中比较资源、进程 incarnation、epoch 和有效期。
     */
    void assertCurrent(OwnershipLease lease);
}
