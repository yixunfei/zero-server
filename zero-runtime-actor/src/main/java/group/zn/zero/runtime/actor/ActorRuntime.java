package group.zn.zero.runtime.actor;

import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
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

    public static List<RuntimeComponentProvider> providers() {
        return List.of(RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_ACTOR)
                .provide(ACTOR_SCHEDULER).require(RuntimeBasics.EXECUTORS).kind(ComponentKind.LOCAL).build(), context ->
                ComponentContribution.builder().bind(ACTOR_SCHEDULER,
                        new ExecutorActorScheduler(context.require(RuntimeBasics.EXECUTORS).actorExecutor())).build()));
    }
}
