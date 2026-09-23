package group.zn.zero.runtime.actor;

import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.actor.scheduler.ActorSchedulerConfig;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;

/** Explicit actor bindings and lazy local providers. */
public final class ActorRuntime {
    public static final ComponentKey<ActorScheduler> ACTOR_SCHEDULER = ComponentKey.single(
            StandardRuntimeCapabilityModel.ACTOR_SCHEDULER, ActorScheduler.class);

    private ActorRuntime() {
    }

    public static RuntimeModule module() {
        return RuntimeModule.of("zero.actor", providers(), ACTOR_SCHEDULER);
    }

    /**
     * 创建显式预算的 Actor 模块描述，不创建线程或调度器；线程安全。
     * @param config 不可变队列预算，不可为空。
     * @return 不可变模块，实际装配时登记调度器关闭责任。
     */
    public static RuntimeModule module(final ActorSchedulerConfig config) {
        return RuntimeModule.of("zero.actor", providers(config), ACTOR_SCHEDULER);
    }

    public static List<RuntimeComponentProvider> providers() {
        return providers(ActorSchedulerConfig.defaults());
    }

    /**
     * 创建惰性 provider，线程安全；装配前不分配运行时资源。
     * @param config 不可变队列预算，不可为空。
     * @return 不可变有序非空 provider 列表，可跨线程共享。
     */
    public static List<RuntimeComponentProvider> providers(final ActorSchedulerConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        return List.of(RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_ACTOR)
                .provide(ACTOR_SCHEDULER).require(RuntimeBasics.EXECUTORS).kind(ComponentKind.LOCAL).build(), context -> {
                    var scheduler = new ExecutorActorScheduler(context.require(RuntimeBasics.EXECUTORS).actorExecutor(), config);
                    return ComponentContribution.builder().bind(ACTOR_SCHEDULER, scheduler)
                            .lifecycle(new SchedulerLifecycle(scheduler)).build();
                }));
    }

    /** Runtime component lifecycle wrapper，不把调度器重复登记为外部 AutoCloseable 资源。 @author zn */
    private static final class SchedulerLifecycle implements Lifecycle {
        /** 被管理的调度器。 */
        private final ExecutorActorScheduler scheduler;
        /** 组件状态；runtime 只在开始/停止阶段调用。 */
        private volatile LifecycleState state = LifecycleState.NEW;
        private SchedulerLifecycle(final ExecutorActorScheduler scheduler) { this.scheduler = scheduler; }
        @Override public LifecycleState state() { return state; }
        @Override public void start() { state = LifecycleState.RUNNING; }
        @Override public void stop() { scheduler.close(); state = LifecycleState.STOPPED; }
    }
}
