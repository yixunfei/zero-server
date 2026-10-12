package group.zn.zero.examples.multiplayer.rpc;

import group.zn.zero.examples.multiplayer.PlayerSession;

/**
 * 精确断线请求；不可变、线程安全。
 * @param session 非空会话。
 * @param traceId 非空追踪标识。
 * @author zn
 */
public record DisconnectRequest(PlayerSession session, String traceId) {
    /**
     * 校验输入，无外部数据变更。
     * @throws group.zn.zero.examples.multiplayer.MultiServerError 必填字段或数值非法。
     */
    public DisconnectRequest {
        RpcInputs.check(session != null, "session");
        RpcInputs.text(traceId, "traceId");
    }
}
