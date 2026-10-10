package group.zn.zero.room;

import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.handler.ActorHandler;
import java.util.Map;
import java.util.WeakHashMap;

/** 每个调度器只安装一次无状态房间路由；弱键和 handler 不延长服务生命周期。 @author zn */
final class RoomDispatchRegistry {
    /** 安装标记；由类监视器保护，值不引用调度器。 */
    private static final Map<ActorScheduler, Boolean> INSTALLED = new WeakHashMap<>();

    private RoomDispatchRegistry() { }

    /** 注册无状态路由；只锁注册过程，命令在所属 lane 执行，真实注册失败向上传播。 */
    static synchronized void register(final ActorScheduler scheduler) {
        if (INSTALLED.containsKey(scheduler)) return;
        scheduler.register(Command.class, ActorHandler.sync((context, message) ->
                ((Command) message.payload()).action().run()));
        INSTALLED.put(scheduler, Boolean.TRUE);
    }

    /** 绑定所属服务的命令；仅在消息指定的 lane 上调用，不跨 lane 修改状态。 */
    record Command(Runnable action) { }
}
