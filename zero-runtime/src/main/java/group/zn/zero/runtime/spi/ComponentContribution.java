package group.zn.zero.runtime.spi;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.health.HealthProbe;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * provider 创建后一次性提交的 bindings、lifecycle 和 health probes。
 *
 * @author zn
 */
public final class ComponentContribution {

    private final Map<BindingKey<?>, Object> bindings;
    private final Lifecycle lifecycle;
    private final Map<HealthPhase, HealthProbe> healthProbes;

    private ComponentContribution(final Builder builder) {
        bindings = Collections.unmodifiableMap(new LinkedHashMap<>(builder.bindings));
        lifecycle = builder.lifecycle;
        healthProbes = Collections.unmodifiableMap(new EnumMap<>(builder.healthProbes));
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<BindingKey<?>, Object> bindings() {
        return bindings;
    }

    public Optional<Lifecycle> lifecycle() {
        return Optional.ofNullable(lifecycle);
    }

    public Map<HealthPhase, HealthProbe> healthProbes() {
        return healthProbes;
    }

    @Override
    public String toString() {
        return "ComponentContribution{bindings=" + bindings.keySet()
                + ", lifecycle=" + (lifecycle != null)
                + ", healthPhases=" + healthProbes.keySet() + '}';
    }

    /**
     * ComponentContribution builder。
     *
     * @author zn
     */
    public static final class Builder {

        private final Map<BindingKey<?>, Object> bindings = new LinkedHashMap<>();
        private Lifecycle lifecycle;
        private final Map<HealthPhase, HealthProbe> healthProbes = new EnumMap<>(HealthPhase.class);

        private Builder() {
        }

        public <T> Builder bind(final ComponentKey<T> key, final T value) {
            putBinding(key, value);
            return this;
        }

        public <T> Builder contribute(final ComponentSetKey<T> key, final T value) {
            putBinding(key, value);
            return this;
        }

        public Builder lifecycle(final Lifecycle componentLifecycle) {
            if (lifecycle != null) {
                throw new IllegalStateException("component lifecycle is already set");
            }
            lifecycle = Objects.requireNonNull(componentLifecycle, "componentLifecycle");
            return this;
        }

        public Builder healthProbe(final HealthPhase phase, final HealthProbe probe) {
            if (healthProbes.putIfAbsent(
                    Objects.requireNonNull(phase, "phase"),
                    Objects.requireNonNull(probe, "probe")) != null) {
                throw new IllegalStateException("component health probe is already set");
            }
            return this;
        }

        public ComponentContribution build() {
            return new ComponentContribution(this);
        }

        private void putBinding(final BindingKey<?> key, final Object value) {
            BindingKey<?> checkedKey = Objects.requireNonNull(key, "key");
            if (bindings.putIfAbsent(checkedKey, Objects.requireNonNull(value, "value")) != null) {
                throw new IllegalStateException("component binding is already set");
            }
        }
    }
}
