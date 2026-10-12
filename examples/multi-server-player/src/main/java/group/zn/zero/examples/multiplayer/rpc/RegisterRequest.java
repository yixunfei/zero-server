package group.zn.zero.examples.multiplayer.rpc;

/**
 * 账号注册请求；不可变、线程安全。
 * @param accountId 非空账号。
 * @param traceId 非空追踪标识。
 * @author zn
 */
public record RegisterRequest(String accountId, String traceId) {
    /**
     * 校验输入，无外部数据变更。
     * @throws group.zn.zero.examples.multiplayer.MultiServerError 必填字段或数值非法。
     */
    public RegisterRequest {
        RpcInputs.text(accountId, "accountId");
        RpcInputs.text(traceId, "traceId");
    }
}
