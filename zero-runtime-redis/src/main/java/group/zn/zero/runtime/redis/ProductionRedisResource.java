package group.zn.zero.runtime.redis;

import group.zn.zero.data.redis.RedisDriverClientFactory;
import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.runtime.production.ProductionAdapterDiagnostic;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterFailures;
import java.time.Duration;
import group.zn.zero.runtime.production.RuntimeAdapterIo;
import group.zn.zero.runtime.spi.ResourceRegistrar;
import java.util.List;
import java.util.Objects;
import redis.clients.jedis.RedisClient;

/** Redis data/cache provider 共享的延迟 client owner。 */
final class ProductionRedisResource {

    private final RedisDriverSettings settings;
    private final Duration operationTimeout;
    private final String failureOwner;
    private final List<ProductionAdapterDiagnostic> diagnostics;
    private RedisClient client;
    private RuntimeAdapterIo monitor;

    ProductionRedisResource(
            final RedisDriverSettings settings,
            final Duration operationTimeout,
            final String failureOwner,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.operationTimeout = Objects.requireNonNull(operationTimeout, "operationTimeout");
        this.failureOwner = Objects.requireNonNull(failureOwner, "failureOwner");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    synchronized RedisClient acquire(final ResourceRegistrar resources) {
        if (client != null) {
            return client;
        }
        try {
            client = Objects.requireNonNull(resources, "resources").register(
                    RedisDriverClientFactory.create(settings, operationTimeout));
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

    synchronized void monitor(final RuntimeAdapterIo value) {
        monitor = Objects.requireNonNull(value, "monitor");
    }

    synchronized RuntimeAdapterIo monitor() {
        return Objects.requireNonNull(monitor, "Redis runtime monitor has not been created");
    }
}
