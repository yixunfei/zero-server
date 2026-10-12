package group.zn.zero.examples.multiplayer.rpc;

/**
 * 逻辑服分配请求；不可变、线程安全。
 * @param uid 正数玩家 ID。
 * @param traceId 非空追踪标识。
 * @author zn
 */
public record AllocationRequest(long uid, String traceId) {
    /**
     * 校验输入，无外部数据变更。
     * @throws group.zn.zero.examples.multiplayer.MultiServerError 必填字段或数值非法。
     */
    public AllocationRequest {
        RpcInputs.check(uid > 0, "uid");
        RpcInputs.text(traceId, "traceId");
    }
}
