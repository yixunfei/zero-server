package group.zn.zero.actor.remote;

import group.zn.zero.actor.ActorMessage;

/**
 * Actor 路由解析器。
 *
 * <p>解析器只做消息到路由的选择，不读取或修改远程 Actor 状态。实现不得在 Actor
 * handler 内执行不可控远程 IO；如需要服务发现快照，应使用外部缓存或显式装配边界。
 *
 * @author zn
 */
@FunctionalInterface
public interface ActorRouteResolver {

    /**
     * 解析 Actor 消息投递路由。
     *
     * @param message Actor 消息；不可为空。
     * @return 投递路由；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 路由失败时抛出，必须绑定 ErrorCode。
     */
    ActorRoute resolve(ActorMessage message);

    /**
     * 返回只投递到本地调度器的路由解析器。
     *
     * @return 本地解析器；不可为空；线程安全。
     */
    static ActorRouteResolver localOnly() {
        return message -> ActorRoute.local(message.laneKey());
    }
}
