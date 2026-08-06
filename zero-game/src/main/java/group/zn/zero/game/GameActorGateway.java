package group.zn.zero.game;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.error.ActorErrorCode;
import group.zn.zero.actor.remote.ActorDispatchOptions;
import group.zn.zero.actor.remote.ActorRoute;
import group.zn.zero.actor.remote.ActorRouteResolver;
import group.zn.zero.actor.remote.ActorRouteKind;
import group.zn.zero.actor.remote.RemoteActorGateway;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.core.error.ZeroException;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 游戏业务 Actor 投递网关。
 *
 * <p>该网关把业务上下文、lane key 和命令对象统一包装成 `ActorMessage`。
 * 网关本身不持有业务状态、线程安全；实际串行化和线程安全由 `ActorScheduler` 保证。
 *
 * @author zn
 */
public final class GameActorGateway {

    /**
     * Actor 调度器。
     */
    private final ActorScheduler actorScheduler;

    /**
     * Actor 路由解析器。
     */
    private final ActorRouteResolver routeResolver;

    /**
     * 远程 Actor 投递网关。
     */
    private final RemoteActorGateway remoteActorGateway;

    /**
     * 默认投递选项。
     */
    private final ActorDispatchOptions defaultOptions;

    /**
     * 创建仅本地投递的游戏业务 Actor 投递网关。
     *
     * @param actorScheduler Actor 调度器；不可为空。
     * @throws NullPointerException 当 Actor 调度器为空时抛出。
     */
    public GameActorGateway(final ActorScheduler actorScheduler) {
        this(
                actorScheduler,
                ActorRouteResolver.localOnly(),
                RemoteActorGateway.unavailable(),
                ActorDispatchOptions.defaults());
    }

    /**
     * 创建支持本地与远程路由的游戏业务 Actor 投递网关。
     *
     * @param actorScheduler Actor 调度器；不可为空。
     * @param routeResolver Actor 路由解析器；不可为空。
     * @param remoteActorGateway 远程 Actor 投递网关；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public GameActorGateway(
            final ActorScheduler actorScheduler,
            final ActorRouteResolver routeResolver,
            final RemoteActorGateway remoteActorGateway) {
        this(actorScheduler, routeResolver, remoteActorGateway, ActorDispatchOptions.defaults());
    }

    /**
     * 创建支持自定义投递选项的游戏业务 Actor 投递网关。
     *
     * @param actorScheduler Actor 调度器；不可为空。
     * @param routeResolver Actor 路由解析器；不可为空。
     * @param remoteActorGateway 远程 Actor 投递网关；不可为空。
     * @param defaultOptions 默认投递选项；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public GameActorGateway(
            final ActorScheduler actorScheduler,
            final ActorRouteResolver routeResolver,
            final RemoteActorGateway remoteActorGateway,
            final ActorDispatchOptions defaultOptions) {
        this.actorScheduler = Objects.requireNonNull(actorScheduler, "actorScheduler");
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver");
        this.remoteActorGateway = Objects.requireNonNull(remoteActorGateway, "remoteActorGateway");
        this.defaultOptions = Objects.requireNonNull(defaultOptions, "defaultOptions");
    }

    /**
     * 投递业务命令到指定 lane。
     *
     * <p>该方法不直接修改业务状态，只提交 Actor 消息。状态修改应发生在对应 Actor handler 内。
     *
     * @param context 请求上下文；不可为空。
     * @param laneKey 目标 lane；不可为空。
     * @param command 命令对象；不可为空。
     * @return 调度完成信号；不可为空；同一 lane 内顺序完成。
     * @throws NullPointerException 当任一参数为空时抛出。
     * @throws group.zn.zero.core.error.ZeroException 当调度失败时抛出，必须绑定 ErrorCode。
     */
    public CompletionStage<Void> dispatch(
            final GameRequestContext context,
            final LaneKey laneKey,
            final Object command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(laneKey, "laneKey");
        Objects.requireNonNull(command, "command");
        ActorMessage message = new ActorMessage(
                messageId(command),
                laneKey,
                context.traceId(),
                command);
        ActorRoute route = Objects.requireNonNull(routeResolver.resolve(message), "actor route");
        validateRoute(message, route);
        if (route.kind() == ActorRouteKind.LOCAL) {
            return actorScheduler.dispatch(message);
        }
        return remoteActorGateway.dispatch(message, route, defaultOptions);
    }

    /**
     * 返回底层 Actor 调度器。
     *
     * @return Actor 调度器；不可为空；线程安全性由实现声明。
     */
    public ActorScheduler actorScheduler() {
        return actorScheduler;
    }

    /**
     * 返回 Actor 路由解析器。
     *
     * @return 路由解析器；不可为空；线程安全性由实现声明。
     */
    public ActorRouteResolver routeResolver() {
        return routeResolver;
    }

    private String messageId(final Object command) {
        return "game-" + command.getClass().getSimpleName();
    }

    private void validateRoute(final ActorMessage message, final ActorRoute route) {
        if (!message.laneKey().equals(route.laneKey())) {
            throw ZeroException.of(
                    ActorErrorCode.ROUTE_NOT_FOUND,
                    "actor route lane mismatch: " + message.laneKey() + " -> " + route.laneKey(),
                    null);
        }
    }
}
