package group.zn.zero.actor.remote;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.error.ActorErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 远程 Actor 投递网关 SPI。
 *
 * <p>该 SPI 只承载消息投递，不暴露远程 Actor 状态引用。实现可以基于 RPC、MQ 或其他
 * transport，但必须把远程 IO 保持在 Actor handler 之外。
 *
 * @author zn
 */
@FunctionalInterface
public interface RemoteActorGateway {

    /**
     * 投递 Actor 消息到远程路由。
     *
     * @param message Actor 消息；不可为空。
     * @param route 远程路由；不可为空。
     * @param options 投递选项；不可为空。
     * @return 投递完成信号；不可为空；失败必须绑定 ErrorCode。
     */
    CompletionStage<Void> dispatch(ActorMessage message, ActorRoute route, ActorDispatchOptions options);

    /**
     * 返回不可用的远程网关占位实现。
     *
     * @return 远程网关；不可为空；线程安全。
     */
    static RemoteActorGateway unavailable() {
        return (message, route, options) -> CompletableFuture.failedFuture(ZeroException.of(
                ActorErrorCode.REMOTE_GATEWAY_UNAVAILABLE,
                "remote actor gateway is not configured",
                null));
    }
}
