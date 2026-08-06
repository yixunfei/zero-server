package group.zn.zero.rpc.spi;

import group.zn.zero.core.spi.ZeroProvider;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import java.util.concurrent.CompletionStage;

/**
 * RPC 传输 SPI。
 *
 * @author zn
 */
public interface RpcTransport extends ZeroProvider {

    /**
     * 发送请求并等待响应。
     *
     * @param request RPC 请求；不可为空。
     * @return 响应结果；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 发送失败或超时时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<RpcResponse> request(RpcRequest request);

    /**
     * 发送单向请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 发送完成信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 发送失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> oneway(RpcRequest request);
}
