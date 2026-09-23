package group.zn.zero.runtime.spi;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import java.util.Objects;

/** Small factories for explicit providers; factories run only after planning succeeds. */
public final class RuntimeProviders {
    private RuntimeProviders() {
    }

    public static RuntimeComponentProvider create(
            final ComponentDescriptor descriptor, final Factory factory) {
        return new Provider(descriptor, factory);
    }

    /** The caller owns supplied values; Lifecycle values participate in runtime start/stop. */
    public static <T> RuntimeComponentProvider value(
            final ComponentId id, final ComponentKey<T> key, final T value, final ComponentKind kind) {
        T checked = Objects.requireNonNull(value, "value");
        return create(ComponentDescriptor.builder(id).provide(key).kind(kind).build(), context -> {
            ComponentContribution.Builder result = ComponentContribution.builder().bind(key, checked);
            if (checked instanceof Lifecycle lifecycle) {
                result.lifecycle(lifecycle);
            }
            return result.build();
        });
    }

    @FunctionalInterface
    public interface Factory {
        ComponentContribution create(ComponentCreationContext context) throws Exception;
    }

    private record Provider(ComponentDescriptor descriptor, Factory factory) implements RuntimeComponentProvider {
        private Provider {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(factory, "factory");
        }

        @Override
        public ComponentContribution create(final ComponentCreationContext context) throws Exception {
            return factory.create(Objects.requireNonNull(context, "context"));
        }
    }
}
