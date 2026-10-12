package group.zn.zero.examples.multiplayer.rpc;

import group.zn.zero.rpc.common.RpcMethod;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.common.RpcService;
import group.zn.zero.examples.multiplayer.AccountBinding;
import group.zn.zero.examples.multiplayer.PlayerSession;

/**
 * 登录服职责的契约草图；本示例仅本地调用，未注册序列化 codec 或远端 handler。
 * idempotent 描述重复调用不增加副作用，不保证返回值相同，也不自动提供请求去重。
 * 线程与数据变更由实现声明；当前 Login/Logic 实现只能在玩家服务创建线程调用。
 * @author zn
 */
@RpcService(name = "multiplayer.login", version = 1)
public interface LoginContract {
    /**
     * 注册账号；重复注册返回业务错误，不覆盖已有绑定。
     * @param request 已校验且不可空的请求。
     * @return 不可空结果；失败含 ErrorCode，成功值为不可变快照。
     * @throws NullPointerException 请求为空；未分类基础设施异常由上层统一处理。
     */
    @RpcMethod(id = 1, idempotent = true)
    RpcResult<AccountBinding> register(RegisterRequest request);

    /**
     * 认证登录；重复请求保留有效会话与路由。
     * @param request 已校验且不可空的请求。
     * @return 不可空结果；失败含 ErrorCode，成功值为不可变快照。
     * @throws NullPointerException 请求为空；未分类基础设施异常由上层统一处理。
     */
    @RpcMethod(id = 2, idempotent = true)
    RpcResult<PlayerSession> login(LoginRequest request);
}
