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
                    var scheduler = new ExecutorActorScheduler(
                            context.require(RuntimeBasics.EXECUTORS).actorExecutor(), config,
                            context.require(RuntimeBasics.EXECUTORS).scheduledExecutor("actor-timeout"),
                            config.executionTimeout());
                    var close = context.resources().register(new SchedulerResource(scheduler));
                    return ComponentContribution.builder().bind(ACTOR_SCHEDULER, scheduler)
                            .lifecycle(new SchedulerLifecycle(close)).build();
                }));
    }

    /** 正常停止和 build 回滚共享一次物理关闭；关闭失败允许再次调用。 @author zn */
    private static final class SchedulerResource implements AutoCloseable {
        /** 被管理的调度器。 */
        private final ExecutorActorScheduler scheduler;
        /** 由资源监视器保护的关闭标志。 */
        private boolean closed;
        private SchedulerResource(final ExecutorActorScheduler scheduler) { this.scheduler = scheduler; }
        @Override public synchronized void close() {
            if (closed) return;
            scheduler.close();
            closed = true;
        }
    }

    /** 生命周期停止与创建期资源回滚共享幂等的调度器关闭责任。 @author zn */
    private static final class SchedulerLifecycle implements Lifecycle {
        /** 被管理的调度器。 */
        private final SchedulerResource resource;
        /** 组件状态；runtime 只在开始/停止阶段调用。 */
        private volatile LifecycleState state = LifecycleState.NEW;
        private SchedulerLifecycle(final SchedulerResource resource) { this.resource = resource; }
        @Override public LifecycleState state() { return state; }
        @Override public synchronized void start() {
            if (state == LifecycleState.RUNNING) return;
            if (state != LifecycleState.NEW) throw group.zn.zero.core.error.ZeroException.of(
                    group.zn.zero.core.error.SystemErrorCode.SYSTEM_ERROR, "closed actor runtime cannot restart", null);
            state = LifecycleState.RUNNING;
        }
        @Override public synchronized void stop() {
            if (state == LifecycleState.STOPPED) return;
            resource.close(); state = LifecycleState.STOPPED;
        }
    }
}
