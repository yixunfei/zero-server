package group.zn.zero.starter.production;

import group.zn.zero.data.redis.RedisDriverClientFactory;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.runtime.spi.ResourceRegistrar;
import java.util.List;
import java.util.Objects;
import redis.clients.jedis.RedisClient;

/** Redis data/cache provider 共享的延迟 client owner。 */
final class ProductionRedisResource {

    private final RedisDriverSettings settings;
    private final ProductionStartupBudget startupBudget;
    private final String failureOwner;
    private final List<ProductionAdapterDiagnostic> diagnostics;
    private RedisClient client;

    ProductionRedisResource(
            final RedisDriverSettings settings,
            final ProductionStartupBudget startupBudget,
            final String failureOwner,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        this.failureOwner = Objects.requireNonNull(failureOwner, "failureOwner");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    synchronized RedisClient acquire(final ResourceRegistrar resources) {
        if (client != null) {
            return client;
        }
        try {
            client = Objects.requireNonNull(resources, "resources").register(
                    RedisDriverClientFactory.create(settings, startupBudget.adapterBudget()));
            return client;
        } catch (RuntimeException | Error failure) {
            ProductionAdapterException safeFailure = ProductionAdapterFailures.sanitize(
                    failureOwner,
                    ProductionAdapterFailurePhase.CLIENT_CREATION,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED.message(),
                    failure);
            diagnostics.forEach(diagnostic -> diagnostic.fail(safeFailure));
            throw safeFailure;
        }
    }

    RedisDriverSettings settings() {
        return settings;
    }

    synchronized RedisClient client() {
        return Objects.requireNonNull(client, "Redis resource has not been acquired");
    }
}
