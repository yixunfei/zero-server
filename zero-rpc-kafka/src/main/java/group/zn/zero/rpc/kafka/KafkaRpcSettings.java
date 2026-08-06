package group.zn.zero.rpc.kafka;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Kafka RPC 适配器配置。
 *
 * @param bootstrapServers Kafka broker 地址。
 * @param clientId Kafka clientId 前缀。
 * @param consumerGroupId 请求消费组 ID。
 * @param topicPrefix RPC topic 前缀。
 * @param replyTopic 当前调用方默认 reply topic。
 * @param pendingCapacity 等待响应的最大请求数。
 * @param pollTimeout Kafka consumer 单次 poll 超时。
 * @param closeTimeout 关闭 consumer worker 的等待时间。
 * @param producerProperties 追加 producer 配置；不可变、无序、可能为空、线程安全。
 * @param consumerProperties 追加 consumer 配置；不可变、无序、可能为空、线程安全。
 * @author zn
 */
public record KafkaRpcSettings(
        String bootstrapServers,
        String clientId,
        String consumerGroupId,
        String topicPrefix,
        String replyTopic,
        int pendingCapacity,
        Duration pollTimeout,
        Duration closeTimeout,
        Map<String, Object> producerProperties,
        Map<String, Object> consumerProperties) {

    /**
     * 创建 Kafka RPC 适配器配置。
     *
     * @throws NullPointerException 当任一必填字段为空时抛出。
     * @throws IllegalArgumentException 当字符串为空白、容量非法或时间非法时抛出。
     */
    public KafkaRpcSettings {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(consumerGroupId, "consumerGroupId");
        Objects.requireNonNull(topicPrefix, "topicPrefix");
        Objects.requireNonNull(replyTopic, "replyTopic");
        Objects.requireNonNull(pollTimeout, "pollTimeout");
        Objects.requireNonNull(closeTimeout, "closeTimeout");
        Objects.requireNonNull(producerProperties, "producerProperties");
        Objects.requireNonNull(consumerProperties, "consumerProperties");
        if (bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers must not be blank");
        }
        if (clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
        if (consumerGroupId.isBlank()) {
            throw new IllegalArgumentException("consumerGroupId must not be blank");
        }
        if (topicPrefix.isBlank()) {
            throw new IllegalArgumentException("topicPrefix must not be blank");
        }
        if (replyTopic.isBlank()) {
            throw new IllegalArgumentException("replyTopic must not be blank");
        }
        if (pendingCapacity <= 0) {
            throw new IllegalArgumentException("pendingCapacity must be positive");
        }
        if (pollTimeout.isNegative() || pollTimeout.isZero()) {
            throw new IllegalArgumentException("pollTimeout must be positive");
        }
        if (closeTimeout.isNegative()) {
            throw new IllegalArgumentException("closeTimeout must not be negative");
        }
        producerProperties = Map.copyOf(producerProperties);
        consumerProperties = Map.copyOf(consumerProperties);
    }

    /**
     * 基于 broker 地址创建默认配置。
     *
     * @param bootstrapServers Kafka broker 地址；不可为空。
     * @return 默认配置；不可为空；线程安全。
     * @throws NullPointerException 当 broker 地址为空时抛出。
     */
    public static KafkaRpcSettings defaults(final String bootstrapServers) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return new KafkaRpcSettings(
                bootstrapServers,
                "zero-rpc-" + suffix,
                "zero-rpc-service",
                "zero.rpc",
                "zero.rpc.reply." + suffix,
                4096,
                Duration.ofMillis(100),
                Duration.ofSeconds(3),
                Map.of(),
                Map.of());
    }

    /**
     * 返回不包含任何配置原值的安全诊断字符串。
     *
     * @return 仅含配置状态与扩展属性数量的安全摘要；不可为空；线程安全。
     */
    @Override
    public String toString() {
        return "KafkaRpcSettings[bootstrapServersConfigured=true"
                + ", clientIdConfigured=true"
                + ", consumerGroupIdConfigured=true"
                + ", topicPrefixConfigured=true"
                + ", replyTopicConfigured=true"
                + ", pendingCapacityConfigured=true"
                + ", pollTimeoutConfigured=true"
                + ", closeTimeoutConfigured=true"
                + ", producerPropertyCount=" + producerProperties.size()
                + ", consumerPropertyCount=" + consumerProperties.size()
                + ']';
    }
}
