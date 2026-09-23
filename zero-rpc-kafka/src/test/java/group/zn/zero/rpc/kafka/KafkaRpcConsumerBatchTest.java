package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** 异步批次提交、失败重投与所有权切换回归。 @author zn */
class KafkaRpcConsumerBatchTest {
    /** 业务和响应未完成前不提交，成功后仅提交实际处理的 offset。 */
    @Test
    void commitsOnlyAfterCompletion() {
        try (var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST)) {
            TopicPartition partition = new TopicPartition("requests", 0);
            consumer.assign(List.of(partition));
            consumer.updateBeginningOffsets(Map.of(partition, 0L));
            var batch = new KafkaRpcConsumerBatch(consumer);
            CompletableFuture<Void> done = new CompletableFuture<>();
            batch.track(records(partition), done);
            batch.commitCompleted();
            assertTrue(consumer.committed(Set.of(partition)).get(partition) == null);
            assertEquals(Set.of(partition), consumer.paused());
            done.complete(null);
            batch.commitCompleted();
            assertEquals(8L, consumer.committed(Set.of(partition)).get(partition).offset());
            assertTrue(consumer.paused().isEmpty());
        }
    }

    /** 异步失败及重平衡不得提交任何在途 offset。 */
    @Test
    void doesNotCommitFailureOrRevokedBatch() {
        try (var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST)) {
            TopicPartition partition = new TopicPartition("requests", 0);
            consumer.assign(List.of(partition));
            var batch = new KafkaRpcConsumerBatch(consumer);
            batch.track(records(partition), CompletableFuture.failedFuture(new IllegalStateException("send failed")));
            assertThrows(CompletionException.class, batch::commitCompleted);
            assertTrue(consumer.committed(Set.of(partition)).get(partition) == null);
            batch.onPartitionsRevoked(List.of(partition));
            assertThrows(KafkaRpcResourceException.class, batch::commitCompleted);
            assertTrue(consumer.committed(Set.of(partition)).get(partition) == null);
        }
    }

    /** 默认组为实例独立组，显式组仍由调用方控制负载均衡。 */
    @Test
    void defaultGroupsAreInstanceSpecific() {
        var first = KafkaRpcSettings.defaults("localhost:9092");
        var second = KafkaRpcSettings.defaults("localhost:9092");
        org.junit.jupiter.api.Assertions.assertNotEquals(first.consumerGroupId(), second.consumerGroupId());
    }

    private ConsumerRecords<String, byte[]> records(final TopicPartition partition) {
        return new ConsumerRecords<>(Map.of(partition,
                List.of(new ConsumerRecord<>(partition.topic(), partition.partition(), 7L, "key", new byte[0]))));
    }
}
