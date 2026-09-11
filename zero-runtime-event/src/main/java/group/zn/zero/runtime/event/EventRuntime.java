package group.zn.zero.runtime.event;

import group.zn.zero.event.bus.EventBus;
import group.zn.zero.event.bus.InMemoryEventBus;
import group.zn.zero.event.deadletter.DeadLetterSink;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;

/** Explicit event bindings and lazy local providers. */
public final class EventRuntime {
    public static final ComponentKey<DeadLetterSink> DEAD_LETTER_SINK = ComponentKey.single(
            StandardRuntimeCapabilityModel.DEAD_LETTER_SINK, DeadLetterSink.class);
    public static final ComponentKey<EventBus> EVENT_BUS = ComponentKey.single(
            StandardRuntimeCapabilityModel.EVENT_BUS, EventBus.class);

    private EventRuntime() {
    }

    public static RuntimeModule module() {
        return RuntimeModule.of("zero.event", providers(), EVENT_BUS);
    }

    public static List<RuntimeComponentProvider> providers() {
        return List.of(
                RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_DEAD_LETTER)
                        .provide(DEAD_LETTER_SINK).kind(ComponentKind.LOCAL).build(), context ->
                        ComponentContribution.builder().bind(DEAD_LETTER_SINK, new InMemoryDeadLetterSink()).build()),
                RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_EVENT_BUS)
                        .provide(EVENT_BUS).require(DEAD_LETTER_SINK).kind(ComponentKind.LOCAL).build(), context ->
                        ComponentContribution.builder().bind(EVENT_BUS,
                                new InMemoryEventBus(context.require(DEAD_LETTER_SINK))).build()));
    }
}
