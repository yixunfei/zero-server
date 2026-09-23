package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.util.List;
import java.util.Objects;

/** Explicit providers, default selections and configuration for one integration module. */
public record RuntimeModule(
        String id,
        List<RuntimeComponentProvider> providers,
        RuntimePreset preset,
        List<ConfigSource> configSources) {

    public RuntimeModule {
        id = RuntimeIdentifiers.requireStableId(id, "moduleId");
        providers = List.copyOf(providers);
        Objects.requireNonNull(preset, "preset");
        configSources = List.copyOf(configSources);
    }

    public static RuntimeModule of(
            final String id, final List<RuntimeComponentProvider> providers, final BindingKey<?>... roots) {
        RuntimePreset.Builder preset = RuntimePreset.builder(id);
        for (RuntimeComponentProvider provider : providers) {
            provider.descriptor().provides().forEach(key -> select(preset, key, provider.descriptor().id()));
        }
        for (BindingKey<?> key : roots) {
            preset.require(key);
        }
        return new RuntimeModule(id, providers, preset.build(), List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void select(
            final RuntimePreset.Builder preset, final BindingKey<?> key, final ComponentId provider) {
        if (key instanceof ComponentKey<?> single) {
            preset.select((ComponentKey) single, provider);
        } else {
            preset.contribute((ComponentSetKey) key, provider);
        }
    }
}
