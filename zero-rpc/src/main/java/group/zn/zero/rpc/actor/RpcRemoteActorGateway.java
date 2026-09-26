package group.zn.zero.rpc.actor;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.error.ActorErrorCode;
import group.zn.zero.actor.remote.ActorDispatchOptions;
import group.zn.zero.actor.remote.ActorRoute;
import group.zn.zero.actor.remote.ActorRouteKind;
import group.zn.zero.actor.remote.RemoteActorGateway;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.spi.RpcTransport;
import group.zn.zero.security.SecurityContextBridge;
import group.zn.zero.security.SecurityMetadataAssertion;
import group.zn.zero.security.SecurityMetadataSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 基于 `RpcTransport` 的远程 Actor 投递网关。
 *
 * <p>第一版使用 oneway RPC 承载状态修改消息：发送完成只表示消息已交给传输层，不表示
 * 远程 Actor 已完成业务状态修改。业务如需结果通知，应由远端 handler 再投递后续 Actor 消息。
 *
 * @author zn
 */
public final class RpcRemoteActorGateway implements RemoteActorGateway {

    /**
     * 默认远程 Actor 方法名。
     */
    public static final String DEFAULT_METHOD_NAME = "dispatchActorMessage";

    /**
     * RPC 传输。
     */
    private final RpcTransport transport;

    /**
     * Actor 消息编解码器。
     */
    private final RpcActorMessageCodec messageCodec;
    /** 应用持有的跨进程身份签名器；本地无签名模式可为空。 */
    private final SecurityMetadataAssertion assertion;

    /**
     * 创建 RPC 远程 Actor 投递网关。
     *
     * @param transport RPC 传输；不可为空。
     * @param messageCodec Actor 消息编解码器；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public RpcRemoteActorGateway(final RpcTransport transport, final RpcActorMessageCodec messageCodec) {
        this(transport, messageCodec, null);
    }

    /**
     * 创建携带可信安全上下文的远程网关，不创建资源，可跨线程调用。
     * @param transport 传输；不可为空。
     * @param messageCodec 消息编码器；不可为空。
     * @param assertion 应用签名器；为空只适用于不要求认证的本地 transport。
     */
    public RpcRemoteActorGateway(final RpcTransport transport, final RpcActorMessageCodec messageCodec,
            final SecurityMetadataAssertion assertion) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.messageCodec = Objects.requireNonNull(messageCodec, "messageCodec");
        this.assertion = assertion;
    }

    /**
     * 通过 RPC oneway 投递 Actor 消息。
     *
     * @param message Actor 消息；不可为空。
     * @param route 远程路由；不可为空。
     * @param options 投递选项；不可为空。
     * @return 发送完成信号；不可为空；失败通过异常完成并绑定 ErrorCode。
     */
    @Override
    public CompletionStage<Void> dispatch(
            final ActorMessage message,
            final ActorRoute route,
            final ActorDispatchOptions options) {
        ActorMessage current = Objects.requireNonNull(message, "message");
        ActorRoute currentRoute = Objects.requireNonNull(route, "route");
        ActorDispatchOptions currentOptions = Objects.requireNonNull(options, "options");
        if (currentRoute.kind() != ActorRouteKind.REMOTE) {
            throw ZeroException.of(
                    ActorErrorCode.ROUTE_NOT_FOUND,
                    "rpc remote actor gateway requires remote route",
                    null);
        }
        Instant timeoutAt = Instant.now().plus(currentOptions.timeout());
        RpcRequest request = new RpcRequest(
                current.messageId(),
                currentOptions.replyTopic(),
                currentRoute.address().ownerServiceName(),
                currentRoute.methodName(),
                current.traceId(),
                timeoutAt,
                RpcMode.ONEWAY,
                currentRoute.requestTopic(),
                currentRoute.consumerGroup(),
                currentRoute.partitionKey().isBlank() ? current.laneKey().value() : currentRoute.partitionKey(),
                securityMetadata(),
                messageCodec.encode(current));
        return transport.oneway(request);
    }

    private SecurityMetadataSnapshot securityMetadata() {
        if (assertion == null) return null;
        var context = SecurityContextBridge.current().orElse(null);
        if (context == null || context.expired(Instant.now())) {
            throw ZeroException.of(group.zn.zero.rpc.error.RpcErrorCode.INVALID_REQUEST,
                    "remote actor requires a valid security context", null);
        }
        return SecurityMetadataAssertion.signed(context, "rpc-actor", assertion);
    }
}
