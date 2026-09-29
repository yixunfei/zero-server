package group.zn.zero.net.kcp;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 跨节点会话迁移屏障；只编排所有权 SPI，不复制密钥或业务帧，不在调用线程阻塞。
 * 流程为 ACTIVE -> FROZEN -> PENDING(snapshot) -> ACTIVE(target)。调用方在 freeze 后停止业务入站，
 * 在 claim 成功前保持目标路径关闭；任何 false 都必须由调用方回滚或关闭会话。
 * @author zn
 */
public final class KcpSessionCoordinator {
    /** 受管所有权服务。 */
    private final KcpSessionServices services;
    /** 创建不产生网络副作用的编排器。 */
    public KcpSessionCoordinator(final KcpSessionServices services) { this.services = Objects.requireNonNull(services); }
    /** @return 当前节点标识；线程安全。 */
    public String nodeId() { return services.nodeId(); }
    /** 原子获取初始所有权；异步完成。 */
    public CompletionStage<Boolean> acquire(final int conv, final long generation) {
        return services.store().acquire(conv, services.nodeId(), generation, services.lease());
    }
    /** 续租当前 ACTIVE/FROZEN 所有权；异步完成。 */
    public CompletionStage<Boolean> renew(final int conv, final long generation) {
        return services.store().renew(conv, services.nodeId(), generation, services.lease());
    }
    /** 冻结当前节点接收，准备生成不含秘密的快照。 */
    public CompletionStage<Boolean> freeze(final int conv, final long generation) {
        return services.store().freeze(conv, services.nodeId(), generation);
    }
    /** 取消尚未提交的冻结。 */
    public CompletionStage<Boolean> unfreeze(final int conv, final long generation) {
        return services.store().unfreeze(conv, services.nodeId(), generation);
    }
    /** 提交快照并创建更高代际的目标 PENDING 所有权。 */
    public CompletionStage<Boolean> migrate(final KcpSessionSnapshot snapshot, final String targetOwner,
            final long targetGeneration) {
        return services.store().migrate(snapshot, targetOwner, targetGeneration, services.lease());
    }
    /** 目标节点原子接管 PENDING 会话。 */
    public CompletionStage<Boolean> claim(final int conv, final long generation) {
        return services.store().claim(conv, services.nodeId(), generation, services.lease());
    }
    /** 释放当前代际并保留墓碑。 */
    public CompletionStage<Boolean> release(final int conv, final long generation) {
        return services.store().release(conv, services.nodeId(), generation);
    }
    /** 读取当前 owner；不返回已过期租约。 */
    public CompletionStage<KcpSessionOwner> owner(final int conv) { return services.store().owner(conv); }
    /** 读取待接管快照；密钥和业务数据永不通过该 SPI 返回。 */
    public CompletionStage<KcpSessionSnapshot> load(final int conv) { return services.store().load(conv); }
}
