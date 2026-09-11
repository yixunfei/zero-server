package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.runtime.postgresql.PostgresqlRuntime;
import group.zn.zero.runtime.production.ProductionAssembly;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RepositoryMinimumConfigTest {
    @Test
    void documentedPostgresqlEnvironmentMustIncludeAnExplicitTable() {
        var variables = new LinkedHashMap<>(Map.of("ZERO_POSTGRESQL_URL", "jdbc:postgresql://127.0.0.1:1/review",
                "ZERO_POSTGRES_USER", "review", "ZERO_POSTGRES_PASSWORD", "review"));
        assertEquals(List.of("zero.postgresql.table"), assembly(variables).diagnose().missingConfigKeys());
        variables.put("ZERO_POSTGRESQL_TABLE", "review_envelopes");
        assertTrue(assembly(variables).diagnose().missingConfigKeys().isEmpty());
    }

    private ProductionAssembly assembly(final Map<String, String> variables) {
        return ProductionAssembly.builder(new MapZeroConfig(Map.of("zero.adapter.data.postgresql.enabled", "true")))
                .configSourceLookups(key -> null, variables::get).install(PostgresqlRuntime.module());
    }
}
