package group.zn.zero.rpc.actor;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import java.util.Objects;

/**
 * RPC 远程 Actor 消息接收器。
 *
 * <p>接收器把 RPC payload 解码为 `ActorMessage` 后投递到本地 `ActorScheduler`。
 * 状态修改仍只发生在本地 Actor handler 内，不向调用方暴露远程 Actor 状态引用。
 *
 * @author zn
 */
public final class RpcRemoteActorReceiver {

    /**
     * RPC handler 注册表。
     */
    private final RpcHandlerRegistry handlerRegistry;

    /**
     * 本地 Actor 调度器。
     */
    private final ActorScheduler actorScheduler;

    /**
     * Actor 消息编解码器。
     */
    private final RpcActorMessageCodec messageCodec;

    /**
     * 创建 RPC 远程 Actor 消息接收器。
     *
     * @param handlerRegistry RPC handler 注册表；不可为空。
     * @param actorScheduler 本地 Actor 调度器；不可为空。
     * @param messageCodec Actor 消息编解码器；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public RpcRemoteActorReceiver(
            final RpcHandlerRegistry handlerRegistry,
            final ActorScheduler actorScheduler,
            final RpcActorMessageCodec messageCodec) {
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry");
        this.actorScheduler = Objects.requireNonNull(actorScheduler, "actorScheduler");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
    }

    /**
     * 注册远程 Actor dispatch RPC handler。
     *
     * @param serviceName RPC 服务名；不可为空。
     * @param methodName RPC 方法名；不可为空。
     * @param topic 传输 topic；可为空。
     * @param group 消费组；可为空。
     * @return 注册句柄；调用 close 后取消注册；不可为空；线程安全性由注册表实现声明。
     */
    public RpcRemoteActorRegistration register(
            final String serviceName,
            final String methodName,
            final String topic,
            final String group) {
        handlerRegistry.register(serviceName, methodName, topic, group, this::handle);
        return new RpcRemoteActorRegistration(handlerRegistry, serviceName, methodName);
    }

    private java.util.concurrent.CompletionStage<RpcResponse> handle(final RpcRequest request) {
        ActorMessage message = messageCodec.decode(request.payload());
        return actorScheduler.dispatch(message)
                .thenApply(ignored -> new RpcResponse(
                        request.correlationId(),
                        request.traceId(),
                        SystemErrorCode.OK,
                        new byte[0]));
    }
}
