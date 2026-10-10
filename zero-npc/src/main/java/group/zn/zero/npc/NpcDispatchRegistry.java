package group.zn.zero.npc;

import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import java.util.Map;
import java.util.WeakHashMap;

/** 每个调度器安装一次无状态路由，命令显式绑定区域；不保留区域或调度器。 @author zn */
final class NpcDispatchRegistry {
    /** 弱安装标记，由类监视器保护。 */
    private static final Map<ActorScheduler, Boolean> INSTALLED = new WeakHashMap<>();
    private NpcDispatchRegistry() { }
    static synchronized void register(final ActorScheduler scheduler) {
        if (INSTALLED.containsKey(scheduler)) return;
        scheduler.register(Command.class, ActorHandler.sync((context, message) ->
                ((Command) message.payload()).action().run()));
        INSTALLED.put(scheduler, Boolean.TRUE);
    }
    /** 在消息指定的 zone lane 上运行的操作。 @author zn */
    record Command(Runnable action) { }
}
