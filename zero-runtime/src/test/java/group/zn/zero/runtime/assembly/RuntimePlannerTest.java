package group.zn.zero.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan.DependencyEdgeKind;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.support.FakeRuntimeProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 最小闭包和确定性 planner 测试。
 */
class RuntimePlannerTest {

    private static final ComponentKey<String> ALPHA = ComponentKey.single("test.alpha", String.class);
    private static final ComponentKey<String> BETA = ComponentKey.single("test.beta", String.class);
    private static final ComponentKey<String> UNUSED = ComponentKey.single("test.unused", String.class);
    private static final ComponentId ALPHA_ID = ComponentId.of("test.provider.alpha");
    private static final ComponentId BETA_ID = ComponentId.of("test.provider.beta");
    private static final ComponentId UNUSED_ID = ComponentId.of("test.provider.unused");

    /**
     * diagnose 只激活根能力的 required 闭包且不调用 create。
     */
    @Test
    void diagnoseShouldPlanMinimumClosureWithoutCreatingProviders() {
        FakeRuntimeProvider alpha = provider(ALPHA_ID, descriptor(ALPHA_ID).provide(ALPHA).build(), ALPHA, "a");
        FakeRuntimeProvider beta = provider(
                BETA_ID, descriptor(BETA_ID).provide(BETA).require(ALPHA).build(), BETA, "b");
        FakeRuntimeProvider unused = provider(
                UNUSED_ID, descriptor(UNUSED_ID).provide(UNUSED).build(), UNUSED, "unused");
        ComponentCatalog catalog = catalog(alpha, beta, unused);
        RuntimePreset preset = RuntimePreset.builder("test-local")
                .select(ALPHA, ALPHA_ID)
                .select(BETA, BETA_ID)
                .select(UNUSED, UNUSED_ID)
                .require(BETA)
                .build();

        RuntimeAssemblyPlan plan = RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .preset(preset)
                .diagnose();

        assertEquals(List.of(ALPHA_ID, BETA_ID), componentIds(plan));
        assertEquals(1, plan.edges().size());
        assertEquals(DependencyEdgeKind.REQUIRED, plan.edges().getFirst().kind());
        assertEquals(0, alpha.createCount());
        assertEquals(0, beta.createCount());
        assertEquals(0, unused.createCount());
    }

    /**
     * provider 注册顺序不同不会改变 plan。
     */
    @Test
    void sameInputsShouldProduceSamePlanAcrossRegistrationOrder() {
        FakeRuntimeProvider alpha = provider(ALPHA_ID, descriptor(ALPHA_ID).provide(ALPHA).build(), ALPHA, "a");
        FakeRuntimeProvider beta = provider(
                BETA_ID, descriptor(BETA_ID).provide(BETA).require(ALPHA).build(), BETA, "b");
        RuntimePreset preset = RuntimePreset.builder("deterministic")
                .require(BETA)
                .select(ALPHA, ALPHA_ID)
                .select(BETA, BETA_ID)
                .build();

        RuntimeAssemblyPlan forward = RuntimeAssembler.builder(catalog(alpha, beta), RuntimeProfile.local())
                .preset(preset)
                .diagnose();
        RuntimeAssemblyPlan reversed = RuntimeAssembler.builder(catalog(beta, alpha), RuntimeProfile.local())
                .preset(preset)
                .diagnose();

        assertEquals(forward, reversed);
    }

    @Test
    void selectionSnapshotShouldUseStableKeyOrder() {
        RuntimeSelection selection = RuntimeSelection.builder()
                .select(BETA, BETA_ID, SelectionSource.programmatic("test"))
                .select(ALPHA, ALPHA_ID, SelectionSource.programmatic("test"))
                .build();

        assertEquals(List.of(ALPHA, BETA), List.copyOf(selection.singles().keySet()));
    }

    /**
     * optional 未选择时不增加节点，显式选择时才注入并形成边。
     */
    @Test
    void optionalDependencyShouldActivateOnlyWhenSelected() {
        FakeRuntimeProvider alpha = provider(ALPHA_ID, descriptor(ALPHA_ID).provide(ALPHA).build(), ALPHA, "a");
        FakeRuntimeProvider beta = provider(
                BETA_ID, descriptor(BETA_ID).provide(BETA).optional(ALPHA).build(), BETA, "b");
        ComponentCatalog catalog = catalog(alpha, beta);
        RuntimeAssemblyPlan withoutOptional = RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .require(BETA)
                .select(BETA, BETA_ID, "test")
                .diagnose();
        RuntimeAssemblyPlan withOptional = RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .require(BETA)
                .select(BETA, BETA_ID, "test")
                .select(ALPHA, ALPHA_ID, "test")
                .diagnose();

        assertEquals(List.of(BETA_ID), componentIds(withoutOptional));
        assertTrue(withoutOptional.edges().isEmpty());
        assertEquals(List.of(ALPHA_ID, BETA_ID), componentIds(withOptional));
        assertEquals(DependencyEdgeKind.OPTIONAL, withOptional.edges().getFirst().kind());
    }

    /**
     * 显式 override 替换 preset 选择并保留 previous provider 诊断。
     */
    @Test
    void overrideShouldRecordPreviousProvider() {
        ComponentId replacementId = ComponentId.of("test.provider.replacement");
        FakeRuntimeProvider alpha = provider(ALPHA_ID, descriptor(ALPHA_ID).provide(ALPHA).build(), ALPHA, "a");
        FakeRuntimeProvider replacement = provider(
                replacementId, descriptor(replacementId).provide(ALPHA).build(), ALPHA, "replacement");
        RuntimePreset preset = RuntimePreset.builder("override-base")
                .require(ALPHA)
                .select(ALPHA, ALPHA_ID)
                .build();

        RuntimeAssemblyPlan plan = RuntimeAssembler.builder(catalog(alpha, replacement), RuntimeProfile.local())
                .preset(preset)
                .overrideSelection(ALPHA, replacementId, "test-override")
                .diagnose();

        RuntimeAssemblyPlan.SelectionReason reason = plan.components().getFirst().selectionReasons().getFirst();
        assertEquals(replacementId, reason.providerId());
        assertEquals(ALPHA_ID, reason.previousProviderId().orElseThrow());
        assertEquals(SelectionSourceKind.OVERRIDE, reason.sourceKind());
    }

    private static ComponentDescriptor.Builder descriptor(final ComponentId id) {
        return ComponentDescriptor.builder(id);
    }

    private static FakeRuntimeProvider provider(
            final ComponentId id,
            final ComponentDescriptor descriptor,
            final ComponentKey<String> key,
            final String value) {
        assertEquals(id, descriptor.id());
        return new FakeRuntimeProvider(descriptor, context -> ComponentContribution.builder()
                .bind(key, value)
                .build());
    }

    private static ComponentCatalog catalog(final FakeRuntimeProvider... providers) {
        ComponentCatalog.Builder builder = ComponentCatalog.builder();
        for (FakeRuntimeProvider provider : providers) {
            builder.register("test-catalog", provider);
        }
        return builder.build();
    }

    private static List<ComponentId> componentIds(final RuntimeAssemblyPlan plan) {
        return plan.components().stream().map(RuntimeAssemblyPlan.ComponentPlan::componentId).toList();
    }
}
