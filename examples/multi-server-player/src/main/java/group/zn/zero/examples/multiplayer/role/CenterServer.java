package group.zn.zero.examples.multiplayer.role;

import group.zn.zero.examples.multiplayer.CenterRouter;
import group.zn.zero.examples.multiplayer.rpc.AllocationRequest;
import group.zn.zero.examples.multiplayer.rpc.AllocationResult;
import group.zn.zero.examples.multiplayer.rpc.CenterContract;
import group.zn.zero.examples.multiplayer.rpc.RegistryRequest;
import group.zn.zero.examples.multiplayer.rpc.RegistryResult;
import group.zn.zero.rpc.common.RpcResult;
import java.util.Objects;

/** 中心服职责的本地契约实现；注册和分配使用同一路由表，线程安全。 @author zn */
public final class CenterServer implements CenterContract {
    /** 当前组合根注入的业务依赖，不由本角色关闭。 */
    private final CenterRouter router;

    /**
     * 保存业务依赖，不创建资源、不改变状态。
     * @param router 非空业务依赖。
     * @throws NullPointerException 依赖为空。
     */
    public CenterServer(final CenterRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    /**
     * 按 UID 返回稳定逻辑服；无实例时返回 NO_LOGIC_SERVER。
     * @param request 非空、已校验请求；线程约束与本类一致。
     * @return 不可空结果；业务异常保留 ErrorCode，状态变化见对应服务。
     * @throws NullPointerException request 为空；其他未分类故障继续交给上层处理。
     */
    @Override
    public RpcResult<AllocationResult> allocate(final AllocationRequest request) {
        Objects.requireNonNull(request, "request");
        return RoleResults.call(() -> new AllocationResult(router.allocate(request.uid()), request.traceId()));
    }

    /**
     * 幂等注册逻辑服 ID，返回共享注册表大小；host/port 仅校验，不进行健康探测。
     * @param request 非空、已校验请求；线程约束与本类一致。
     * @return 不可空结果；业务异常保留 ErrorCode，状态变化见对应服务。
     * @throws NullPointerException request 为空；其他未分类故障继续交给上层处理。
     */
    @Override
    public RpcResult<RegistryResult> register(final RegistryRequest request) {
        Objects.requireNonNull(request, "request");
        return RoleResults.call(() -> new RegistryResult(request.instanceId(), router.register(request.instanceId()), request.traceId()));
    }
}
