package group.zn.zero.examples.repository;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.mapping.ZeroDataObjectMetadata;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.data.repository.RepositoryDefinition;
import group.zn.zero.data.repository.RepositoryRequest;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.mongo.MongoRuntime;
import group.zn.zero.runtime.postgresql.PostgresqlRuntime;
import group.zn.zero.runtime.production.ProductionAssembly;
import group.zn.zero.runtime.redis.RedisRuntime;
import java.util.Map;

/** Shared composition root for contract and resilience tests. */
final class RepositoryTestRuntime {
    private RepositoryTestRuntime() { }

    static CrudRepository<String, Balance> repository(
            final GameRuntime runtime, final RepositoryDefinition<String, Balance> definition) {
        return runtime.require(DataRuntime.REPOSITORIES).create(new RepositoryRequest<>("game", definition));
    }

    static RepositoryDefinition<String, Balance> definition(final String name, final String namespace) {
        var original = RepositoryCompositionApplication.BALANCES;
        var metadata = original.metadata();
        var scoped = new ZeroDataObjectMetadata(metadata.objectType(), namespace, metadata.collection(),
                metadata.schemaVersion(), metadata.keyPrefix(), metadata.compositeKey(), metadata.keyCodecType(),
                metadata.idGeneratorType(), metadata.fields(), metadata.keyParts(), metadata.idField(), metadata.versionField());
        return new RepositoryDefinition<>(name, String.class, Balance.class, scoped, original.codec(), original.codecVersion());
    }

    static GameRuntime runtime(final String backend) {
        var roles = DataRuntime.repositories(Map.of("game", backend));
        if (backend.equals("local")) {
            return RuntimeBasics.builder().install(DataRuntime.module()).install(roles).build();
        }
        var module = switch (backend) {
            case "mongo" -> MongoRuntime.module();
            case "postgresql" -> PostgresqlRuntime.module();
            case "redis" -> RedisRuntime.module();
            default -> throw new IllegalArgumentException("backend must be local, mongo, postgresql or redis");
        };
        return ProductionAssembly.builder(new MapZeroConfig(Map.of(
                        "zero.adapter.data." + backend + ".enabled", "true",
                        "zero.adapter.startup-timeout-millis", "2000")))
                .configSourceLookups(key -> null, System::getenv).install(module).install(roles).build();
    }
}
