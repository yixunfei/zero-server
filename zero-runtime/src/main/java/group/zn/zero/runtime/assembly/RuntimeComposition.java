package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.List;
import java.util.Objects;

/** Explicit, single-use composition root. No classpath discovery or component construction in install/diagnose. */
public final class RuntimeComposition {
    private final List<RuntimeModule> modules = new ArrayList<>();
    private final List<RuntimeComponentProvider> providers = new ArrayList<>();
    private final List<Consumer<RuntimeAssembler.Builder>> selections = new ArrayList<>();
    private final List<ConfigSource> configSources = new ArrayList<>();
    private RuntimeProfile profile;
    private RuntimePreset preset;
    private Duration assemblyTimeout = Duration.ofSeconds(30);
    private Duration startupTimeout = Duration.ofSeconds(30);
    private boolean consumed;

    private RuntimeComposition(final RuntimeProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public static RuntimeComposition builder(final RuntimeProfile profile) {
        return new RuntimeComposition(profile);
    }

    public RuntimeComposition install(final RuntimeModule module) {
        mutable();
        modules.add(Objects.requireNonNull(module, "module"));
        return this;
    }

    public RuntimeComposition register(final RuntimeComponentProvider provider) {
        mutable();
        providers.add(Objects.requireNonNull(provider, "provider"));
        return this;
    }

    public RuntimeComposition preset(final RuntimePreset value, final RuntimeProfile policy) {
        mutable();
        preset = Objects.requireNonNull(value, "preset");
        profile = Objects.requireNonNull(policy, "profile");
        return this;
    }

    public RuntimeComposition require(final BindingKey<?> key) {
        mutable();
        Objects.requireNonNull(key, "key");
        selections.add(builder -> builder.require(key));
        return this;
    }

    public <T> RuntimeComposition override(final ComponentKey<T> key, final ComponentId providerId) {
        mutable();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(providerId, "providerId");
        selections.add(builder -> builder.overrideSelection(key, providerId, "composition"));
        return this;
    }

    public <T> RuntimeComposition replace(final ComponentKey<T> key, final T value) {
        return replace(ComponentId.of("application." + key.id()), key, value);
    }

    public <T> RuntimeComposition replace(final ComponentId id, final ComponentKey<T> key, final T value) {
        register(RuntimeProviders.value(id, key, value, ComponentKind.BUSINESS));
        return override(key, id);
    }

    public <T> RuntimeComposition contribute(final ComponentSetKey<T> key, final ComponentId providerId) {
        mutable();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(providerId, "providerId");
        selections.add(builder -> builder.contribute(key, providerId, "composition").require(key));
        return this;
    }

    /** Explicit sources take precedence over module defaults. */
    public RuntimeComposition configSource(final ConfigSource source) {
        mutable();
        configSources.add(Objects.requireNonNull(source, "source"));
        return this;
    }

    public RuntimeComposition assemblyTimeout(final Duration timeout) {
        mutable();
        assemblyTimeout = positive(timeout);
        return this;
    }

    public RuntimeComposition startupTimeout(final Duration timeout) {
        mutable();
        startupTimeout = positive(timeout);
        return this;
    }

    public RuntimeAssemblyPlan diagnose() {
        mutable();
        return assembler().diagnose();
    }

    public GameRuntime build() {
        mutable();
        consumed = true;
        return assembler().build();
    }

    private RuntimeAssembler.Builder assembler() {
        ComponentCatalog.Builder catalog = ComponentCatalog.builder();
        modules.forEach(module -> module.providers().forEach(provider -> catalog.register(module.id(), provider)));
        providers.forEach(provider -> catalog.register("application", provider));
        RuntimeAssembler.Builder result = RuntimeAssembler.builder(catalog.build(), profile)
                .assemblyTimeout(assemblyTimeout).startupTimeout(startupTimeout);
        if (preset != null) {
            result.preset(preset);
        }
        for (RuntimeModule module : modules) {
            result.selection(module.preset().selection());
            module.preset().requirements().forEach(result::require);
        }
        selections.forEach(selection -> selection.accept(result));
        configSources.forEach(result::configSource);
        modules.forEach(module -> module.configSources().forEach(result::configSource));
        return result;
    }

    private Duration positive(final Duration duration) {
        Duration checked = Objects.requireNonNull(duration, "duration");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException("runtime timeout must be positive");
        }
        return checked;
    }

    private void mutable() {
        if (consumed) {
            throw new IllegalStateException("runtime composition has already been consumed");
        }
    }
}
