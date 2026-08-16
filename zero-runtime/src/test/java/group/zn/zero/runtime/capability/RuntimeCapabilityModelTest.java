package group.zn.zero.runtime.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.runtime.api.BindingCardinality;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RuntimeCapabilityModel 的逻辑闭包和 catalog 对齐测试。 */
class RuntimeCapabilityModelTest {

    private static final MavenCoordinate CORE = MavenCoordinate.zero("zero-core");

    @Test
    void shouldResolveDeterministicDependencyAndArtifactClosure() {
        RuntimeCapabilityModel model = modelBuilder()
                .capability(capability("zero.input", List.of(), "zero-core"))
                .capability(capability("zero.service", List.of("zero.input"), "zero-game"))
                .build();

        assertEquals(
                List.of("zero.input", "zero.service"),
                model.closure(List.of("zero.service")).stream().map(RuntimeCapability::id).toList());
        assertEquals(
                List.of(CORE, MavenCoordinate.zero("zero-game")),
                model.artifactsFor(List.of("zero.service")));
    }

    @Test
    void shouldRejectUnknownDependencyAndCycleBeforeUse() {
        RuntimeCapabilityModel.Builder unknown = modelBuilder()
                .capability(capability("zero.service", List.of("zero.missing"), "zero-game"));
        assertThrows(IllegalArgumentException.class, unknown::build);

        RuntimeCapabilityModel.Builder cyclic = modelBuilder()
                .capability(capability("zero.first", List.of("zero.second"), "zero-core"))
                .capability(capability("zero.second", List.of("zero.first"), "zero-core"));
        assertThrows(IllegalArgumentException.class, cyclic::build);
    }

    @Test
    void shouldValidateTypedDescriptorsAgainstLogicalProviderModel() {
        ComponentId inputProvider = ComponentId.of("zero.local.input");
        ComponentId serviceProvider = ComponentId.of("zero.local.service");
        ComponentKey<String> input = ComponentKey.single("zero.input", String.class);
        ComponentKey<Runnable> service = ComponentKey.single("zero.service", Runnable.class);
        RuntimeCapabilityModel model = modelBuilder()
                .capability(capability("zero.input", List.of(), "zero-core"))
                .capability(capability("zero.service", List.of("zero.input"), "zero-game"))
                .provider(provider(inputProvider, "zero.input"))
                .provider(provider(serviceProvider, "zero.service"))
                .build();

        model.validateDescriptors(List.of(
                ComponentDescriptor.builder(inputProvider).provide(input).build(),
                ComponentDescriptor.builder(serviceProvider).provide(service).require(input).build()), "local");

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class, () ->
                model.validateDescriptors(List.of(
                        ComponentDescriptor.builder(inputProvider).provide(input).build(),
                        ComponentDescriptor.builder(serviceProvider).provide(service).build()), "local"));
        assertTrue(mismatch.getMessage().contains("dependencies"));
    }

    @Test
    void shouldValidateProviderSpecificDependenciesWithoutChangingCapabilityClosure() {
        ComponentId providerId = ComponentId.of("zero.production.transport");
        ComponentKey<String> log = ComponentKey.single("zero.log", String.class);
        ComponentKey<Runnable> transport = ComponentKey.single("zero.transport", Runnable.class);
        RuntimeCapabilityModel model = modelBuilder()
                .capability(capability("zero.log", List.of(), "zero-log"))
                .capability(capability("zero.transport", List.of(), "zero-rpc"))
                .provider(new RuntimeProviderCapability(
                        providerId,
                        List.of("zero.transport"),
                        List.of("zero.log"),
                        List.of("production"),
                        List.of(MavenCoordinate.zero("zero-transport-adapter"))))
                .build();

        model.validateDescriptor(
                ComponentDescriptor.builder(providerId).provide(transport).require(log).build(),
                "production");
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> model.validateDescriptor(
                        ComponentDescriptor.builder(providerId).provide(transport).build(),
                        "production"));

        assertTrue(mismatch.getMessage().contains("dependencies"));
        assertEquals(List.of("zero.transport"),
                model.closure(List.of("zero.transport")).stream().map(RuntimeCapability::id).toList());
        assertEquals(
                List.of(
                        MavenCoordinate.zero("zero-log"),
                        MavenCoordinate.zero("zero-rpc"),
                        MavenCoordinate.zero("zero-transport-adapter")),
                model.artifactsForProviders(List.of(providerId)));
    }

    @Test
    void standardModelShouldExposeStableLocalVocabulary() {
        RuntimeCapabilityModel model = StandardRuntimeCapabilityModel.instance();

        assertEquals("zero.standard", model.id());
        assertEquals(20, model.capabilities().size());
        assertEquals(21, model.providers().size());
        assertEquals(
                BindingCardinality.MULTIPLE,
                model.capability(StandardRuntimeCapabilityModel.DATA_SERVICES)
                        .orElseThrow()
                        .cardinality());
        assertEquals(
                List.of("external-test", "production", "standalone"),
                model.capability(StandardRuntimeCapabilityModel.NETWORK_LIFECYCLE)
                        .orElseThrow()
                        .profiles());
        assertTrue(model.provider(StandardRuntimeCapabilityModel.LOCAL_RPC)
                .orElseThrow()
                .profiles()
                .contains(StandardRuntimeCapabilityModel.PROFILE_PRODUCTION));
        assertEquals(
                List.of(StandardRuntimeCapabilityModel.LOG_APPENDER),
                model.provider(StandardRuntimeCapabilityModel.PRODUCTION_KAFKA_RPC)
                        .orElseThrow()
                        .requires());
        assertEquals(
                List.of(StandardRuntimeCapabilityModel.REDIS_RESOURCE),
                model.provider(StandardRuntimeCapabilityModel.PRODUCTION_REDIS_DATA)
                        .orElseThrow()
                        .requires());
        assertEquals(
                MavenCoordinate.zero("zero-actor"),
                model.artifactsFor(List.of(StandardRuntimeCapabilityModel.ACTOR_SCHEDULER)).getFirst());
    }

    private RuntimeCapabilityModel.Builder modelBuilder() {
        return RuntimeCapabilityModel.builder("test.model");
    }

    private RuntimeCapability capability(
            final String id,
            final List<String> requires,
            final String artifactId) {
        return new RuntimeCapability(
                id,
                BindingCardinality.SINGLE,
                requires,
                List.of("local"),
                List.of(MavenCoordinate.zero(artifactId)));
    }

    private RuntimeProviderCapability provider(final ComponentId providerId, final String capabilityId) {
        return new RuntimeProviderCapability(
                providerId, List.of(capabilityId), List.of(), List.of("local"), List.of());
    }
}
