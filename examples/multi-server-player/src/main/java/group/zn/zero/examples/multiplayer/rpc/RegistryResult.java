package group.zn.zero.examples.multiplayer.rpc;

/**
 * 逻辑服注册结果；不可变、线程安全。
 * @param instanceId 非空实例 ID。
 * @param instanceCount 正数实例总数。
 * @param traceId 非空追踪标识。
 * @author zn
 */
public record RegistryResult(String instanceId, int instanceCount, String traceId) {
    /**
     * 校验输入，无外部数据变更。
     * @throws group.zn.zero.examples.multiplayer.MultiServerError 必填字段或数值非法。
     */
    public RegistryResult {
        RpcInputs.text(instanceId, "instanceId");
        RpcInputs.check(instanceCount > 0, "instanceCount");
        RpcInputs.text(traceId, "traceId");
    }
}
