package group.zn.zero.examples.multiplayer.role;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.common.RpcResult;
import java.util.function.Supplier;

/** 本地契约调用的统一业务错误边界；未知异常继续上抛，不吞掉诊断。 @author zn */
final class RoleResults {
    private RoleResults() { }

    /** 同步执行；保留业务错误码，不承担异步调度或重试。 */
    static <T> RpcResult<T> call(final Supplier<T> action) {
        try {
            return RpcResult.success(action.get());
        } catch (ZeroException failure) {
            return RpcResult.failure(failure.errorCode(), failure.getMessage());
        }
    }
}
