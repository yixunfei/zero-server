package group.zn.zero.actor.remote;

/**
 * Actor 路由类型。
 *
 * @author zn
 */
public enum ActorRouteKind {

    /**
     * 本地 Actor 调度器路由。
     */
    LOCAL,

    /**
     * 远程 Actor gateway 路由。
     */
    REMOTE
}
