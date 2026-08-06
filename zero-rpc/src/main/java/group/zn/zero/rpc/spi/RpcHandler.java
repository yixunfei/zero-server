package group.zn.zero.rpc.spi;

import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * RPC 请求处理器。
 *
 * @author zn
 */
@FunctionalInterface
public interface RpcHandler {

    /**
     * 处理 RPC 请求。
     *
     * @param request RPC 请求；不可为空。
     * @return RPC 响应阶段；不可为空；线程安全性由实现声明。
     */
    CompletionStage<RpcResponse> handle(RpcRequest request);

    /**
     * 创建同步处理器。
     *
     * @param handler 同步处理函数；不可为空。
     * @return RPC 请求处理器；不可为空；线程安全性由函数实现决定。
     */
    static RpcHandler sync(final Function<RpcRequest, RpcResponse> handler) {
        Function<RpcRequest, RpcResponse> current = Objects.requireNonNull(handler, "handler");
        return request -> CompletableFuture.completedFuture(Objects.requireNonNull(current.apply(request), "response"));
    }
}
