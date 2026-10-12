package group.zn.zero.examples.multiplayer.rpc;

/**
 * 逻辑服分配结果；不可变、线程安全。
 * @param logicServerId 非空实例 ID。
 * @param traceId 非空追踪标识。
 * @author zn
 */
public record AllocationResult(String logicServerId, String traceId) {
    /**
     * 校验输入，无外部数据变更。
     * @throws group.zn.zero.examples.multiplayer.MultiServerError 必填字段或数值非法。
     */
    public AllocationResult {
        RpcInputs.text(logicServerId, "logicServerId");
        RpcInputs.text(traceId, "traceId");
    }
}
