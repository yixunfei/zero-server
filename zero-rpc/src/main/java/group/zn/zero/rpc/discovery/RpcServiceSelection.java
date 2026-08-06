package group.zn.zero.rpc.discovery;

import java.time.Instant;
import java.util.Objects;

/**
 * RPC 服务实例选择结果。
 *
 * @param query 查询条件。
 * @param instance 被选中的实例。
 * @param candidateCount 候选实例数量。
 * @param selectedAt 选择时间。
 * @author zn
 */
public record RpcServiceSelection(
        RpcServiceQuery query,
        RpcServiceInstance instance,
        int candidateCount,
        Instant selectedAt) {

    /**
     * 创建 RPC 服务实例选择结果。
     *
     * @throws NullPointerException 当查询条件、实例或选择时间为空时抛出。
     * @throws IllegalArgumentException 当候选数量非法时抛出。
     */
    public RpcServiceSelection {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(instance, "instance");
        if (candidateCount <= 0) {
            throw new IllegalArgumentException("candidateCount must be positive");
        }
        Objects.requireNonNull(selectedAt, "selectedAt");
    }
}
