package group.zn.zero.examples.multiplayer.rpc;

import group.zn.zero.examples.multiplayer.PlayerSession;

/**
 * 创建角色请求；不可变、线程安全。
 * @param session 待校验的当前会话。
 * @param name 非空角色名。
 * @param traceId 非空追踪标识。
 * @author zn
 */
public record CreateCharacterRequest(PlayerSession session, String name, String traceId) {
    /**
     * 校验输入，无外部数据变更。
     * @throws group.zn.zero.examples.multiplayer.MultiServerError 必填字段或数值非法。
     */
    public CreateCharacterRequest {
        RpcInputs.check(session != null, "session");
        RpcInputs.text(name, "name");
        RpcInputs.text(traceId, "traceId");
    }
}
