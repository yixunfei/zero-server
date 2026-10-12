package group.zn.zero.examples.multiplayer.rpc;

/**
 * 本地教学登录请求；不可变、线程安全。
 * @param accountId 非空账号。
 * @param token 非空教学凭据。
 * @param traceId 非空追踪标识。
 * @author zn
 */
public record LoginRequest(String accountId, String token, String traceId) {
    /**
     * 校验输入，无外部数据变更。
     * @throws group.zn.zero.examples.multiplayer.MultiServerError 必填字段或数值非法。
     */
    public LoginRequest {
        RpcInputs.text(accountId, "accountId");
        RpcInputs.text(token, "token");
        RpcInputs.text(traceId, "traceId");
    }
}
