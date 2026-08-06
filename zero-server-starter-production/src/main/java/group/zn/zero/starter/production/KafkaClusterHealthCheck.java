package group.zn.zero.starter.production;

import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;

/**
 * Kafka broker 健康检查。
 *
 * <p>该检查只验证 broker 元数据可读取，不创建 topic、不发送业务消息，也不输出 broker 地址。
 *
 * @author zn
 */
final class KafkaClusterHealthCheck implements ProductionHealthProbe {

    /**
     * Kafka RPC 配置。
     */
    private final KafkaRpcSettings settings;

    /** 经过白名单校验的 Kafka 公共客户端属性。 */
    private final Map<String, Object> clientProperties;

    /**
     * 创建 Kafka broker 健康检查。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param clientProperties 经过白名单校验的公共客户端属性；不可为空，调用后会复制。
     */
    KafkaClusterHealthCheck(
            final KafkaRpcSettings settings,
            final Map<String, Object> clientProperties) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clientProperties = Map.copyOf(Objects.requireNonNull(clientProperties, "clientProperties"));
    }

    /**
     * 执行 Kafka broker 健康检查。
     *
     * @param timeout 当前剩余启动预算；必须为正，不可为空。
     * @throws ProductionAdapterException 当 broker 不可用或检查超时时抛出安全异常。
     */
    @Override
    public void check(final Duration timeout) {
        Duration currentTimeout = requirePositive(timeout);
        long deadlineNanos = deadlineAfter(currentTimeout);
        Properties properties = adminProperties(currentTimeout);
        AdminClient adminClient = null;
        Throwable failure = null;
        boolean interrupted = false;
        try {
            adminClient = AdminClient.create(properties);
            adminClient.describeCluster()
                    .nodes()
                    .get(remaining(deadlineNanos).toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException currentFailure) {
            interrupted = true;
            failure = currentFailure;
        } catch (ExecutionException | TimeoutException | RuntimeException | Error currentFailure) {
            failure = currentFailure;
        }
        failure = closeAdmin(adminClient, deadlineNanos, failure);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw safeFailure(failure);
        }
    }

    /**
     * 构造与真实 Admin health 调用完全一致的 Kafka 属性。
     *
     * <p>该包级冷路径 seam 用于在不连接 Kafka 的测试中验证公共安全属性传播。返回对象可变、无序、
     * 非空且非线程安全，可能包含敏感认证值，只能传给 Kafka driver，不得记录或写入诊断报告。
     *
     * @param timeout 当前剩余启动预算；必须为正，不可为空。
     * @return Admin client 属性；不可为空，可变、无序、非线程安全。
     * @throws NullPointerException timeout 为空时抛出。
     * @throws IllegalArgumentException timeout 非正时抛出。
     */
    Properties adminProperties(final Duration timeout) {
        int timeoutMillis = boundedMillis(requirePositive(timeout));
        Properties properties = new Properties();
        properties.putAll(clientProperties);
        properties.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers());
        properties.setProperty(AdminClientConfig.CLIENT_ID_CONFIG, settings.clientId() + "-health");
        properties.setProperty(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, Integer.toString(timeoutMillis));
        properties.setProperty(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, Integer.toString(timeoutMillis));
        return properties;
    }

    private Throwable closeAdmin(
            final AdminClient adminClient,
            final long deadlineNanos,
            final Throwable primaryFailure) {
        if (adminClient == null) {
            return primaryFailure;
        }
        try {
            adminClient.close(remaining(deadlineNanos));
            return primaryFailure;
        } catch (RuntimeException | Error closeFailure) {
            if (primaryFailure == null) {
                return closeFailure;
            }
            primaryFailure.addSuppressed(closeFailure);
            return primaryFailure;
        }
    }

    private ProductionAdapterException safeFailure(final Throwable failure) {
        return ProductionAdapterFailures.sanitize(
                ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC,
                ProductionAdapterFailurePhase.STARTUP_HEALTH,
                ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED,
                ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED.message(),
                failure);
    }

    private Duration requirePositive(final Duration timeout) {
        Duration current = Objects.requireNonNull(timeout, "timeout");
        if (current.isZero() || current.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return current;
    }

    private int boundedMillis(final Duration timeout) {
        long millis = Math.max(1L, timeout.toMillis());
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    private long deadlineAfter(final Duration timeout) {
        long now = System.nanoTime();
        long nanos = timeout.toNanos();
        return now > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : now + nanos;
    }

    private Duration remaining(final long deadlineNanos) {
        return Duration.ofNanos(Math.max(0L, deadlineNanos - System.nanoTime()));
    }
}
