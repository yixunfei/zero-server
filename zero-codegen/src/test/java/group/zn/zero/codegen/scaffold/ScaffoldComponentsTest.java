package group.zn.zero.codegen.scaffold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.runtime.capability.MavenCoordinate;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScaffoldComponentsTest {
    private final ScaffoldComponents components = new ScaffoldComponents(StandardRuntimeCapabilityModel.instance());

    @Test
    void minimalSelectionOnlyUsesBootstrap() {
        var result = components.resolve(List.of(), List.of());
        assertEquals(List.of("bootstrap"), result.components());
        assertEquals(List.of(MavenCoordinate.zero("zero-runtime-bootstrap")), result.artifacts());
        assertEquals(List.of("zero.config", "zero.executors"), result.capabilities());
    }

    @Test
    void mandatoryCapabilitiesAndDependencyClosureRemainSelected() {
        var result = components.resolve(List.of(StandardRuntimeCapabilityModel.LOG_APPENDER), List.of("event", "actor"));
        assertEquals(List.of("bootstrap", "actor", "event", "log"), result.components());
        assertTrue(result.capabilities().contains(StandardRuntimeCapabilityModel.TERMINAL_LOG_SINK));
        assertTrue(result.capabilities().contains(StandardRuntimeCapabilityModel.DEAD_LETTER_SINK));
        assertFalse(result.artifacts().contains(MavenCoordinate.zero("zero-runtime-data")));
    }

    @Test
    void redisAndCustomActorUseExplicitProviders() {
        var result = components.resolve(List.of(), List.of("redis", "custom-actor", "redis"));
        assertEquals(List.of("bootstrap", "actor", "redis", "custom-actor"), result.components());
        assertTrue(result.external());
        assertTrue(result.providers().contains("application.actor"));
        assertFalse(result.providers().contains("zero.local.actor"));
        assertFalse(result.providers().contains("zero.local.repositories"));
        assertTrue(result.providers().contains("zero.data.repository-catalog"));
        assertTrue(result.capabilities().contains(StandardRuntimeCapabilityModel.REPOSITORIES));
        assertThrows(IllegalArgumentException.class, () -> components.resolve(List.of(), List.of("unknown")));
        assertThrows(IllegalArgumentException.class, () -> components.resolve(List.of(), List.of("")));
    }

    @Test
    void discoverySelectionIncludesTheResolverInstalledByItsIntegrationModule() {
        var result = components.resolve(List.of(StandardRuntimeCapabilityModel.RPC_SERVICE_RESOLVER), List.of());
        assertEquals(List.of("bootstrap", "discovery"), result.components());
        assertTrue(result.providers().containsAll(List.of("zero.discovery.local", "zero.discovery.rpc-resolver")));
        assertTrue(result.capabilities().containsAll(List.of(StandardRuntimeCapabilityModel.SERVICE_DISCOVERY,
                StandardRuntimeCapabilityModel.RPC_SERVICE_RESOLVER)));
    }

    @Test
    void centerLogicSelectionUsesKafkaWithoutDatabaseOrDiscovery() {
        var result = components.resolve(List.of(), List.of("rpc", "kafka"));
        assertEquals(List.of("bootstrap", "log", "kafka"), result.components());
        assertTrue(result.external());
        assertFalse(result.providers().contains("zero.local.rpc"));
        assertFalse(result.artifacts().contains(MavenCoordinate.zero("zero-runtime-nacos")));
        assertFalse(result.capabilities().contains(StandardRuntimeCapabilityModel.REPOSITORIES));
    }

    @Test
    void distributedSelectionReplacesLocalDiscoveryAndKeepsAllNamedDataSources() {
        var result = components.resolve(List.of(), List.of("discovery", "nacos", "mongo", "postgresql", "redis"));
        assertFalse(result.components().contains("discovery"));
        assertTrue(result.providers().contains("zero.production.nacos-rpc-resolver"));
        assertEquals(List.of("redis", "mongo", "postgresql"), ScaffoldComponents.repositorySources(result.components()));
        assertEquals(1, result.providers().stream().filter("zero.data.repository-catalog"::equals).count());
    }
}
