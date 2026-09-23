package group.zn.zero.runtime.assembly;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.runtime.api.BindingKey;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ResourceRegistrar;
import group.zn.zero.runtime.spi.RuntimeDeadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 按拓扑串行 create、即时登记资源并原子提交 contribution。
 */
final class RuntimeBuildTransaction {

    DefaultGameRuntime build(
            final PlannedRuntime planned,
            final RuntimeDeadline assemblyDeadline,
            final Duration startupTimeout,
            final LongSupplier nanoTime) {
        BuildResourceLedger resources = new BuildResourceLedger();
        RuntimeBindings.Mutable bindings = RuntimeBindings.mutable();
        RuntimeReportTracker tracker = new RuntimeReportTracker(planned);
        List<BuiltComponent> components = new ArrayList<>();
        Map<Lifecycle, ComponentId> lifecycleOwners = new IdentityHashMap<>();
        for (ComponentCatalog.RegisteredProvider provider : planned.orderedProviders()) {
            createProvider(
                    planned, assemblyDeadline, resources, bindings, tracker, components, lifecycleOwners, provider);
        }
        tracker.state(group.zn.zero.runtime.api.RuntimeState.BUILT);
        return new DefaultGameRuntime(
                planned.plan(), bindings.freeze(), components, resources, tracker, startupTimeout, nanoTime);
    }

    private void createProvider(
            final PlannedRuntime planned,
            final RuntimeDeadline assemblyDeadline,
            final BuildResourceLedger resources,
            final RuntimeBindings.Mutable bindings,
            final RuntimeReportTracker tracker,
            final List<BuiltComponent> components,
            final Map<Lifecycle, ComponentId> lifecycleOwners,
            final ComponentCatalog.RegisteredProvider provider) {
        ComponentDescriptor descriptor = provider.descriptor();
        ResourceRegistrar registrar = resources.registrar(descriptor.id());
        long startedAt = System.nanoTime();
        try {
            // 两个 provider 之间耗尽预算也必须回滚此前已取得的资源。
            ensureBudget(assemblyDeadline, descriptor.id());
            ComponentContribution contribution = createContribution(
                    planned, assemblyDeadline, bindings, registrar, provider);
            validateContribution(descriptor, contribution, lifecycleOwners);
            ensureBudget(assemblyDeadline, descriptor.id());
            bindings.commit(contribution.bindings());
            components.add(new BuiltComponent(descriptor.id(), contribution));
            tracker.created(descriptor.id(), elapsed(startedAt), contribution.lifecycle().isPresent());
        } catch (Throwable failure) {
            tracker.createFailed(descriptor.id(), elapsed(startedAt));
            rollbackBuild(descriptor.id(), failure, resources, tracker);
        } finally {
            BuildResourceLedger.seal(registrar);
        }
    }

    private ComponentContribution createContribution(
            final PlannedRuntime planned,
            final RuntimeDeadline assemblyDeadline,
            final RuntimeBindings.Mutable bindings,
            final ResourceRegistrar registrar,
            final ComponentCatalog.RegisteredProvider provider) throws Exception {
        DefaultComponentCreationContext context = new DefaultComponentCreationContext(
                provider.descriptor(),
                bindings,
                planned.config().component(provider.descriptor().id()),
                registrar,
                assemblyDeadline);
        return Objects.requireNonNull(provider.provider().create(context), "component contribution");
    }

    private void validateContribution(
            final ComponentDescriptor descriptor,
            final ComponentContribution contribution,
            final Map<Lifecycle, ComponentId> lifecycleOwners) {
        if (!descriptor.provides().equals(contribution.bindings().keySet())) {
            throw invalidContribution(descriptor.id(), "bindings=mismatch");
        }
        for (Map.Entry<BindingKey<?>, Object> binding : contribution.bindings().entrySet()) {
            if (!binding.getKey().type().isInstance(binding.getValue())) {
                throw invalidContribution(descriptor.id(), "binding-type=" + binding.getKey().id());
            }
        }
        if (!descriptor.healthPhases().equals(contribution.healthProbes().keySet())) {
            throw invalidContribution(descriptor.id(), "health=mismatch");
        }
        contribution.lifecycle().ifPresent(lifecycle -> {
            ComponentId existing = lifecycleOwners.putIfAbsent(lifecycle, descriptor.id());
            if (existing != null) {
                throw invalidContribution(descriptor.id(), "lifecycle=duplicate-identity");
            }
        });
    }

    private void ensureBudget(final RuntimeDeadline deadline, final ComponentId componentId) {
        if (deadline.expired()) {
            throw RuntimeAssemblyException.failure(
                    RuntimeErrorCode.RUNTIME_STARTUP_TIMEOUT,
                    RuntimeFailurePhase.CREATE,
                    componentId,
                    "component=" + componentId);
        }
    }

    private void rollbackBuild(
            final ComponentId componentId,
            final Throwable failure,
            final BuildResourceLedger resources,
            final RuntimeReportTracker tracker) {
        RuntimeAssemblyException primary = normalizeCreateFailure(componentId, failure);
        primary = resources.closeAll(primary);
        tracker.failed(primary);
        primary.withReport(tracker.snapshot(resources));
        throw primary;
    }

    private RuntimeAssemblyException normalizeCreateFailure(
            final ComponentId componentId,
            final Throwable failure) {
        RuntimeErrorCode errorCode = RuntimeErrorCode.RUNTIME_COMPONENT_CREATE_FAILED;
        if (failure instanceof RuntimeAssemblyException assemblyFailure) {
            if (assemblyFailure.errorCode() == RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID) {
                errorCode = RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID;
            } else if (assemblyFailure.errorCode() == RuntimeErrorCode.RUNTIME_STARTUP_TIMEOUT) {
                errorCode = RuntimeErrorCode.RUNTIME_STARTUP_TIMEOUT;
            }
        }
        return RuntimeAssemblyException.failure(
                errorCode,
                RuntimeFailurePhase.CREATE,
                componentId,
                "component=" + componentId);
    }

    private RuntimeAssemblyException invalidContribution(
            final ComponentId componentId,
            final String context) {
        return RuntimeAssemblyException.failure(
                RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID,
                RuntimeFailurePhase.CREATE,
                componentId,
                context);
    }

    private long elapsed(final long startedAt) {
        return Math.max(0L, System.nanoTime() - startedAt);
    }
}
