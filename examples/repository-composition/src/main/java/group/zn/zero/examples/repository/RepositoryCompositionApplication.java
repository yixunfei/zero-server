package group.zn.zero.examples.repository;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfigLoader;
import group.zn.zero.data.repository.RepositoryDefinition;
import group.zn.zero.data.repository.RepositoryRequest;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.mongo.MongoRuntime;
import group.zn.zero.runtime.postgresql.PostgresqlRuntime;
import group.zn.zero.runtime.production.ProductionAssembly;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import group.zn.zero.runtime.redis.RedisRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class RepositoryCompositionApplication {
    public static final RepositoryDefinition<String, Balance> BALANCES = RepositoryDefinition.of(
            "balances", String.class, Balance.class, new BalanceCodec(), 1);

    private RepositoryCompositionApplication() { }

    public static void main(final String[] args) {
        String backend = args.length == 0 ? "local" : args[0];
        try (GameRuntime runtime = runtime(backend)) {
            runtime.start();
            var repository = runtime.require(DataRuntime.REPOSITORIES)
                    .create(new RepositoryRequest<>("game", BALANCES));
            var service = new BalanceService(repository);
            String id = "example-" + UUID.randomUUID();
            try {
                service.credit(id, 10).toCompletableFuture().join();
                Balance saved = service.credit(id, 5).toCompletableFuture().join();
                if (saved.amount() != 15 || saved.version() != 2) {
                    throw new IllegalStateException("repository business result mismatch");
                }
                System.out.println("repository-composition=ok|backend=" + backend + "|amount=15|version=2");
            } finally {
                repository.deleteById(id).toCompletableFuture().join();
            }
        }
    }

    public static GameRuntime runtime(final String backend) {
        var roles = DataRuntime.repositories(Map.of("game", backend));
        if (backend.equals("local")) {
            return RuntimeBasics.builder().install(DataRuntime.module()).install(roles).build();
        }
        String enabled = switch (backend) {
            case "mongo" -> ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED;
            case "postgresql" -> ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_POSTGRESQL_ENABLED;
            case "redis" -> ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_REDIS_ENABLED;
            default -> throw new IllegalArgumentException("backend must be local, mongo, postgresql or redis");
        };
        var values = new LinkedHashMap<>(ZeroConfigLoader.loadStandard().asMap());
        values.put(enabled, "true");
        var assembly = ProductionAssembly.builder("external-test", new MapZeroConfig(values), ZeroRuntimeExecutors.direct())
                .install(roles);
        switch (backend) {
            case "mongo" -> assembly.install(MongoRuntime.module());
            case "postgresql" -> assembly.install(PostgresqlRuntime.module());
            case "redis" -> assembly.install(RedisRuntime.module());
            default -> throw new IllegalStateException("unsupported repository backend");
        }
        return assembly.build();
    }
}
