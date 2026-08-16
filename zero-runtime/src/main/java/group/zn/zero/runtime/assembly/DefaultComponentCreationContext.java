package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.config.ComponentConfig;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ResourceRegistrar;
import group.zn.zero.runtime.spi.RuntimeDeadline;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 只允许读取 descriptor 已声明依赖的创建上下文。
 */
final class DefaultComponentCreationContext implements ComponentCreationContext {

    private final ComponentDescriptor descriptor;
    private final RuntimeBindings.Mutable bindings;
    private final ComponentConfig config;
    private final ResourceRegistrar resources;
    private final RuntimeDeadline assemblyDeadline;

    DefaultComponentCreationContext(
            final ComponentDescriptor descriptor,
            final RuntimeBindings.Mutable bindings,
            final ComponentConfig config,
            final ResourceRegistrar resources,
            final RuntimeDeadline assemblyDeadline) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.config = Objects.requireNonNull(config, "config");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.assemblyDeadline = Objects.requireNonNull(assemblyDeadline, "assemblyDeadline");
    }

    @Override
    public <T> T require(final ComponentKey<T> key) {
        ComponentKey<T> checked = Objects.requireNonNull(key, "key");
        ensureDeclared(descriptor.requires().contains(checked), checked.id());
        return bindings.require(checked);
    }

    @Override
    public <T> Optional<T> optional(final ComponentKey<T> key) {
        ComponentKey<T> checked = Objects.requireNonNull(key, "key");
        ensureDeclared(descriptor.optional().contains(checked), checked.id());
        return bindings.optional(checked);
    }

    @Override
    public <T> List<T> requireAll(final ComponentSetKey<T> key) {
        ComponentSetKey<T> checked = Objects.requireNonNull(key, "key");
        boolean declared = descriptor.requires().contains(checked) || descriptor.optional().contains(checked);
        ensureDeclared(declared, checked.id());
        return bindings.requireAll(checked);
    }

    @Override
    public ComponentConfig config() {
        return config;
    }

    @Override
    public ResourceRegistrar resources() {
        return resources;
    }

    @Override
    public RuntimeDeadline assemblyDeadline() {
        return assemblyDeadline;
    }

    private void ensureDeclared(final boolean declared, final String keyId) {
        if (!declared) {
            throw RuntimeAssemblyException.failure(
                    RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID,
                    RuntimeFailurePhase.CREATE,
                    descriptor.id(),
                    "undeclared-key=" + keyId);
        }
    }
}
