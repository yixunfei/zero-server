package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** 真实 broker 验证积压消费、未确认关闭和重启重投。 @author zn */
class KafkaRpcAcknowledgementExternalIT {
    /** 请求完成前不提交，关闭不会等待未完成业务，复用组重启后重新投递。 */
    @Test
    void backlogSurvivesShutdownBeforeAcknowledgement() throws Exception {
        String broker = System.getProperty("zero.kafka.bootstrapServers",
                System.getenv("ZERO_KAFKA_BOOTSTRAP_SERVERS"));
        String suffix = UUID.randomUUID().toString();
        String topic = "hardening-" + suffix;
        String group = "hardening-group-" + suffix;
        KafkaRpcSettings settings = new KafkaRpcSettings(broker, "hardening-" + suffix, group,
                "hardening", "reply-" + suffix, 16, Duration.ofMillis(50), Duration.ofSeconds(5),
                Map.of(), Map.of());
        TopicPartition partition = new TopicPartition(topic, 0);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", broker))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
            try {
                HoldingListener first = new HoldingListener();
                try (var gateway = new ApacheKafkaRpcMessageGateway(settings)) {
                    gateway.send(new KafkaRpcMessage(topic, "key", new byte[] {1})).toCompletableFuture()
                            .get(15, TimeUnit.SECONDS);
                    gateway.subscribe(topic, group, first);
                    assertTrue(first.entered.await(15, TimeUnit.SECONDS), "new group must consume backlog");
                    assertTrue(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS).isEmpty(), "pending processing must not be committed");
                }
                HoldingListener restarted = new HoldingListener();
                try (var gateway = new ApacheKafkaRpcMessageGateway(settings)) {
                    gateway.subscribe(topic, group, restarted);
                    assertTrue(restarted.entered.await(15, TimeUnit.SECONDS), "uncommitted request must replay");
                    restarted.done.complete(null);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    Long offset = null;
                    while (System.nanoTime() < deadline) {
                        var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                                .get(5, TimeUnit.SECONDS);
                        if (offsets.get(partition) != null) {
                            offset = offsets.get(partition).offset();
                            break;
                        }
                        Thread.sleep(25);
                    }
                    assertEquals(1L, offset);
                }
                first.done.complete(null);
            } finally {
                admin.deleteTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
                admin.deleteConsumerGroups(List.of(group)).all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    /** 模拟尚未完成的请求处理阶段。 */
    private static final class HoldingListener implements KafkaRpcMessageListener {
        /** 消息进入处理边界的信号。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** 在测试显式完成前持续占有消费确认。 */
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        /** 仅异步入口可表达消费确认。 */
        @Override public void onMessage(final KafkaRpcMessage message) {
            throw new UnsupportedOperationException("asynchronous listener required");
        }
        /** 捕获消息并返回待确认阶段。 */
        @Override public CompletionStage<Void> onMessageAsync(final KafkaRpcMessage message) {
            entered.countDown();
            return done;
        }
    }
}
