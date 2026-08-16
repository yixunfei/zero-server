package group.zn.zero.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.support.FakeRuntimeProvider;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * planner 缺失、冲突、循环、基数和 policy 失败测试。
 */
class RuntimePlannerValidationTest {

    private static final ComponentKey<String> ALPHA = ComponentKey.single("validation.alpha", String.class);
    private static final ComponentKey<String> BETA = ComponentKey.single("validation.beta", String.class);
    private static final ComponentSetKey<Runnable> HOOKS =
            ComponentSetKey.multiple("validation.hooks", Runnable.class);
    private static final ComponentId ALPHA_ID = ComponentId.of("validation.provider.alpha");
    private static final ComponentId BETA_ID = ComponentId.of("validation.provider.beta");

    @Test
    void duplicateProviderIdShouldFailAtCatalogRegistration() {
        FakeRuntimeProvider first = stringProvider(ALPHA_ID, descriptor(ALPHA_ID).provide(ALPHA).build(), ALPHA);
        FakeRuntimeProvider second = stringProvider(ALPHA_ID, descriptor(ALPHA_ID).provide(ALPHA).build(), ALPHA);
        ComponentCatalog.Builder catalog = ComponentCatalog.builder().register("test", first);

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> catalog.register("test", second));

        assertEquals(RuntimeErrorCode.RUNTIME_DUPLICATE_PROVIDER, failure.errorCode());
    }

    @Test
    void missingAndUnknownSelectionsShouldFailBeforeCreate() {
        FakeRuntimeProvider alpha = stringProvider(ALPHA_ID, descriptor(ALPHA_ID).provide(ALPHA).build(), ALPHA);
        ComponentCatalog catalog = catalog(alpha);
        RuntimeAssemblyException missing = assertThrows(
                RuntimeAssemblyException.class,
                () -> RuntimeAssembler.builder(catalog, RuntimeProfile.local()).require(ALPHA).diagnose());
        RuntimeSelection unknownSelection = RuntimeSelection.builder()
                .select(BETA, ComponentId.of("validation.provider.unknown"), SelectionSource.programmatic("test"))
                .build();
        RuntimeAssemblyException unknown = assertThrows(
                RuntimeAssemblyException.class,
                () -> RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                        .require(ALPHA)
                        .select(ALPHA, ALPHA_ID, "test")
                        .selection(unknownSelection)
                        .diagnose());

        assertEquals(RuntimeErrorCode.RUNTIME_MISSING_CAPABILITY, missing.errorCode());
        assertEquals(RuntimeErrorCode.RUNTIME_UNKNOWN_PROVIDER, unknown.errorCode());
        assertEquals(0, alpha.createCount());
    }

    @Test
    void atomicProvidesShouldRejectDuplicateSingleBinding() {
        ComponentDescriptor alphaDescriptor = descriptor(ALPHA_ID).provide(ALPHA).build();
        ComponentDescriptor betaDescriptor = descriptor(BETA_ID).provide(BETA).provide(ALPHA).build();
        FakeRuntimeProvider alpha = stringProvider(ALPHA_ID, alphaDescriptor, ALPHA);
        FakeRuntimeProvider beta = new FakeRuntimeProvider(betaDescriptor, context -> ComponentContribution.builder()
                .bind(BETA, "b")
                .bind(ALPHA, "duplicate")
                .build());

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> RuntimeAssembler.builder(catalog(alpha, beta), RuntimeProfile.local())
                        .require(ALPHA)
                        .require(BETA)
                        .select(ALPHA, ALPHA_ID, "test")
                        .select(BETA, BETA_ID, "test")
                        .diagnose());

        assertEquals(RuntimeErrorCode.RUNTIME_DUPLICATE_BINDING, failure.errorCode());
    }

    @Test
    void conflictAndCycleShouldHaveStableErrors() {
        FakeRuntimeProvider alphaConflict = stringProvider(
                ALPHA_ID,
                descriptor(ALPHA_ID).provide(ALPHA).conflictWith(BETA_ID).build(),
                ALPHA);
        FakeRuntimeProvider beta = stringProvider(BETA_ID, descriptor(BETA_ID).provide(BETA).build(), BETA);
        RuntimeAssemblyException conflict = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(catalog(alphaConflict, beta)).diagnose());
        FakeRuntimeProvider alphaCycle = stringProvider(
                ALPHA_ID,
                descriptor(ALPHA_ID).provide(ALPHA).require(BETA).build(),
                ALPHA);
        FakeRuntimeProvider betaCycle = stringProvider(
                BETA_ID,
                descriptor(BETA_ID).provide(BETA).require(ALPHA).build(),
                BETA);
        RuntimeAssemblyException cycle = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(catalog(alphaCycle, betaCycle)).diagnose());

        assertEquals(RuntimeErrorCode.RUNTIME_COMPONENT_CONFLICT, conflict.errorCode());
        assertEquals(RuntimeErrorCode.RUNTIME_DEPENDENCY_CYCLE, cycle.errorCode());
        assertTrue(cycle.getMessage().contains(ALPHA_ID.value()));
        assertTrue(cycle.getMessage().contains(BETA_ID.value()));
    }

    @Test
    void multipleBindingShouldKeepExplicitProvidersInStableOrder() {
        ComponentId zuluId = ComponentId.of("validation.provider.zulu");
        ComponentId echoId = ComponentId.of("validation.provider.echo");
        FakeRuntimeProvider zulu = runnableProvider(zuluId);
        FakeRuntimeProvider echo = runnableProvider(echoId);

        var runtime = RuntimeAssembler.builder(catalog(zulu, echo), RuntimeProfile.local())
                .require(HOOKS)
                .contribute(HOOKS, zuluId, "test")
                .contribute(HOOKS, echoId, "test")
                .build();

        assertEquals(List.of(echoId, zuluId), runtime.plan().components().stream()
                .map(component -> component.componentId())
                .toList());
        assertEquals(2, runtime.requireAll(HOOKS).size());
        runtime.close();
    }

    @Test
    void profileShouldRejectForbiddenKindsAndMissingStartupHealth() {
        ComponentDescriptor externalDescriptor = descriptor(ALPHA_ID)
                .provide(ALPHA)
                .kind(ComponentKind.EXTERNAL)
                .build();
        FakeRuntimeProvider external = stringProvider(ALPHA_ID, externalDescriptor, ALPHA);
        RuntimeAssemblyException localFailure = assertThrows(
                RuntimeAssemblyException.class,
                () -> single(catalog(external), RuntimeProfile.local()).diagnose());
        RuntimeAssemblyException healthFailure = assertThrows(
                RuntimeAssemblyException.class,
                () -> single(catalog(external), RuntimeProfile.externalTest()).diagnose());

        assertEquals(RuntimeErrorCode.RUNTIME_POLICY_REJECTED, localFailure.errorCode());
        assertEquals(RuntimeErrorCode.RUNTIME_POLICY_REJECTED, healthFailure.errorCode());
    }

    @Test
    void productionShouldRejectLocalProviderForCriticalCapability() {
        ComponentDescriptor localDescriptor = descriptor(ALPHA_ID)
                .provide(ALPHA)
                .kind(ComponentKind.LOCAL)
                .build();
        FakeRuntimeProvider local = stringProvider(ALPHA_ID, localDescriptor, ALPHA);

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> single(catalog(local), RuntimeProfile.production(Set.of(ALPHA))).diagnose());

        assertEquals(RuntimeErrorCode.RUNTIME_POLICY_REJECTED, failure.errorCode());
    }

    @Test
    void externalProfileShouldAcceptDeclaredStartupHealth() {
        ComponentDescriptor descriptor = descriptor(ALPHA_ID)
                .provide(ALPHA)
                .kind(ComponentKind.EXTERNAL)
                .health(HealthPhase.STARTUP)
                .build();
        FakeRuntimeProvider external = new FakeRuntimeProvider(descriptor, context -> ComponentContribution.builder()
                .bind(ALPHA, "a")
                .healthProbe(HealthPhase.STARTUP, request -> java.util.concurrent.CompletableFuture.completedFuture(
                        group.zn.zero.runtime.health.HealthResult.healthy("ready")))
                .build());

        assertEquals(List.of(ALPHA_ID), single(catalog(external), RuntimeProfile.externalTest())
                .diagnose()
                .components()
                .stream()
                .map(component -> component.componentId())
                .toList());
    }

    private static RuntimeAssembler.Builder assembler(final ComponentCatalog catalog) {
        return RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .require(ALPHA)
                .require(BETA)
                .select(ALPHA, ALPHA_ID, "test")
                .select(BETA, BETA_ID, "test");
    }

    private static RuntimeAssembler.Builder single(
            final ComponentCatalog catalog,
            final RuntimeProfile profile) {
        return RuntimeAssembler.builder(catalog, profile)
                .require(ALPHA)
                .select(ALPHA, ALPHA_ID, "test");
    }

    private static ComponentDescriptor.Builder descriptor(final ComponentId id) {
        return ComponentDescriptor.builder(id);
    }

    private static FakeRuntimeProvider stringProvider(
            final ComponentId id,
            final ComponentDescriptor descriptor,
            final ComponentKey<String> key) {
        assertEquals(id, descriptor.id());
        return new FakeRuntimeProvider(descriptor, context -> ComponentContribution.builder()
                .bind(key, id.value())
                .build());
    }

    private static FakeRuntimeProvider runnableProvider(final ComponentId id) {
        ComponentDescriptor descriptor = descriptor(id).provide(HOOKS).build();
        return new FakeRuntimeProvider(descriptor, context -> ComponentContribution.builder()
                .contribute(HOOKS, () -> { })
                .build());
    }

    private static ComponentCatalog catalog(final FakeRuntimeProvider... providers) {
        ComponentCatalog.Builder builder = ComponentCatalog.builder();
        for (FakeRuntimeProvider provider : providers) {
            builder.register("validation-catalog", provider);
        }
        return builder.build();
    }
}
