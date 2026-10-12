package group.zn.zero.examples.multiplayer.rpc;

/**
 * 逻辑服注册草图，不执行远端健康探测；不可变、线程安全。
 * @param instanceId 非空实例 ID。
 * @param host 非空主机标识。
 * @param port 1 至 65535 的端口。
 * @param traceId 非空追踪标识。
 * @author zn
 */
public record RegistryRequest(String instanceId, String host, int port, String traceId) {
    /**
     * 校验输入，无外部数据变更。
     * @throws group.zn.zero.examples.multiplayer.MultiServerError 必填字段或数值非法。
     */
    public RegistryRequest {
        RpcInputs.text(instanceId, "instanceId");
        RpcInputs.text(host, "host");
        RpcInputs.check(port > 0 && port <= 65535, "port");
        RpcInputs.text(traceId, "traceId");
    }
}
