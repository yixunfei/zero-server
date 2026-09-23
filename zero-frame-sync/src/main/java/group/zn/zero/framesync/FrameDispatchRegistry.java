package group.zn.zero.framesync;

import group.zn.zero.actor.scheduler.ActorScheduler;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/** 每个调度器安装一个无状态帧命令路由器，生命周期与调度器一致。 @author zn */
final class FrameDispatchRegistry {
    /** 弱键不延长调度器生命周期；值和处理器均不引用对局或调度器。 */
    private static final Map<ActorScheduler, Boolean> REGISTERED = new WeakHashMap<>();

    private FrameDispatchRegistry() { }

    /**
     * 幂等安装路由器；同步保护注册表，不在锁内执行业务。
     * @param scheduler 调度器；不可为空。
     */
    static synchronized void register(ActorScheduler scheduler) {
        if (REGISTERED.containsKey(scheduler)) return;
        scheduler.register(Command.class, (context, message) -> {
            ((Command) message.payload()).action().run();
            return CompletableFuture.completedFuture(null);
        });
        REGISTERED.put(scheduler, Boolean.TRUE);
    }

    /** 已绑定目标对局的命令，只在消息指定的 lane 上调用。 */
    record Command(Runnable action) { }
}
