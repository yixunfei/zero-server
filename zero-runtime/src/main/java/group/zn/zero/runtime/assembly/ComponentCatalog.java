package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.internal.RuntimeIdentifiers;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 显式 provider allowlist；注册不表示选择或创建。
 *
 * @author zn
 */
public final class ComponentCatalog {

    private final Map<ComponentId, RegisteredProvider> providers;

    private ComponentCatalog(final Builder builder) {
        providers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.providers));
        validateBindingSignatures(providers.values());
        validateConfigKeys(providers.values());
    }

    public static Builder builder() {
        return new Builder();
    }

    public int size() {
        return providers.size();
    }

    public List<ComponentId> componentIds() {
        return providers.keySet().stream().sorted().toList();
    }

    Optional<RegisteredProvider> provider(final ComponentId componentId) {
        return Optional.ofNullable(providers.get(componentId));
    }

    Collection<RegisteredProvider> providers() {
        return providers.values();
    }

    private static void validateBindingSignatures(final Collection<RegisteredProvider> registrations) {
        Map<String, BindingKey<?>> signatures = new TreeMap<>();
        for (RegisteredProvider registration : registrations) {
            ComponentDescriptor descriptor = registration.descriptor();
            List<BindingKey<?>> keys = new ArrayList<>();
            keys.addAll(descriptor.provides());
            keys.addAll(descriptor.requires());
            keys.addAll(descriptor.optional());
            for (BindingKey<?> key : keys) {
                BindingKey<?> existing = signatures.putIfAbsent(key.id(), key);
                if (existing != null && !existing.equals(key)) {
                    throw RuntimeAssemblyException.failure(
                            RuntimeErrorCode.RUNTIME_DUPLICATE_BINDING,
                            RuntimeFailurePhase.REGISTRATION,
                            descriptor.id(),
                            "key=" + key.id());
                }
            }
        }
    }

    private static void validateConfigKeys(final Collection<RegisteredProvider> registrations) {
        Map<String, ConfigKey<?>> logicalKeys = new TreeMap<>();
        Map<String, ConfigKey<?>> aliases = new TreeMap<>();
        for (RegisteredProvider registration : registrations) {
            for (ConfigKey<?> key : registration.descriptor().configSchema().keys()) {
                if (logicalKeys.putIfAbsent(key.logicalName(), key) != null) {
                    throw duplicateConfig(key);
                }
                for (ConfigSourceKind kind : key.acceptedSources()) {
                    String aliasKey = kind.name() + ':' + key.aliasFor(kind);
                    if (aliases.putIfAbsent(aliasKey, key) != null) {
                        throw duplicateConfig(key);
                    }
                }
            }
        }
    }

    private static RuntimeAssemblyException duplicateConfig(final ConfigKey<?> key) {
        return RuntimeAssemblyException.failure(
                RuntimeErrorCode.RUNTIME_DUPLICATE_CONFIG_KEY,
                RuntimeFailurePhase.REGISTRATION,
                key.owner(),
                "key=" + key.logicalName());
    }

    /** 已冻结的 provider 注册信息。 */
    record RegisteredProvider(
            RuntimeComponentProvider provider,
            ComponentDescriptor descriptor,
            String catalogSource,
            String providerType) {
    }

    /** ComponentCatalog builder。 */
    public static final class Builder {

        private final Map<ComponentId, RegisteredProvider> providers = new TreeMap<>();

        private Builder() {
        }

        public Builder register(final String catalogSource, final RuntimeComponentProvider provider) {
            RuntimeComponentProvider checkedProvider = Objects.requireNonNull(provider, "provider");
            ComponentDescriptor descriptor;
            try {
                descriptor = Objects.requireNonNull(checkedProvider.descriptor(), "provider.descriptor");
            } catch (Throwable failure) {
                throw RuntimeAssemblyException.failure(
                        RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID,
                        RuntimeFailurePhase.REGISTRATION,
                        null,
                        "descriptor=invalid");
            }
            String source = RuntimeIdentifiers.requireStableId(catalogSource, "catalogSource");
            RegisteredProvider registration = new RegisteredProvider(
                    checkedProvider, descriptor, source, checkedProvider.getClass().getName());
            if (providers.putIfAbsent(descriptor.id(), registration) != null) {
                throw RuntimeAssemblyException.failure(
                        RuntimeErrorCode.RUNTIME_DUPLICATE_PROVIDER,
                        RuntimeFailurePhase.REGISTRATION,
                        descriptor.id(),
                        "component=" + descriptor.id());
            }
            return this;
        }

        public ComponentCatalog build() {
            return new ComponentCatalog(this);
        }
    }
}
