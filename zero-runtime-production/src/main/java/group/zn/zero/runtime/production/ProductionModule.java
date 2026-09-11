package group.zn.zero.runtime.production;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.util.List;

/** Resolved providers and safe diagnostics; selecting this module never creates a client. */
public record ProductionModule(
        List<RuntimeComponentProvider> providers,
        List<ConfigSource> configSources,
        List<ProductionAdapterDiagnostic> diagnostics) {
    public ProductionModule {
        providers = List.copyOf(providers);
        configSources = List.copyOf(configSources);
        diagnostics = List.copyOf(diagnostics);
    }

    public static ProductionModule single(
            final RuntimeComponentProvider provider,
            final List<ConfigSource> sources,
            final ProductionAdapterDiagnostic diagnostic) {
        return new ProductionModule(provider == null ? List.of() : List.of(provider), sources, List.of(diagnostic));
    }

    public void configure(final RuntimeComposition composition, final String profile) {
        for (RuntimeComponentProvider provider : providers) {
            if (StandardRuntimeCapabilityModel.instance().provider(provider.descriptor().id()).isPresent()) {
                StandardRuntimeCapabilityModel.instance().validateDescriptor(provider.descriptor(), profile);
            }
            composition.register(provider);
            provider.descriptor().provides().forEach(key -> select(composition, key, provider));
        }
        configSources.forEach(composition::configSource);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void select(
            final RuntimeComposition composition, final BindingKey<?> key, final RuntimeComponentProvider provider) {
        if (key instanceof ComponentKey<?> single) {
            composition.override((ComponentKey) single, provider.descriptor().id()).require(single);
        } else {
            composition.contribute((ComponentSetKey) key, provider.descriptor().id());
        }
    }
}
