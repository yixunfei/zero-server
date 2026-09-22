package group.zn.zero.rpc.kafka;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * Consumer 线程独占的异步批次确认器；暂停取新消息期间继续 poll 维持消费组心跳。
 * 重平衡丢弃旧批次确认，未提交消息可重复投递；业务须保证幂等。
 * @author zn
 */
final class KafkaRpcConsumerBatch implements ConsumerRebalanceListener {
    /** 所有 consumer 操作仅在所属 poll 线程执行。 */
    private final Consumer<String, byte[]> consumer;
    /** 本批次实际读取的 offset 上界，绝不使用 consumer 的隐含 position 提交。 */
    private final Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
    /** 当前处理完成阶段；空表示没有未确认批次。 */
    private CompletableFuture<Void> pending;
    /** 有在途批次时发生重平衡，必须重建 consumer 才能恢复保留分区的读取位置。 */
    private boolean invalidated;

    /** 创建线程封闭的批次确认器。 */
    KafkaRpcConsumerBatch(final Consumer<String, byte[]> consumer) {
        this.consumer = consumer;
    }

    /** 记录实际取到的消息与完成信号，并暂停分区，调用方必须是 consumer 线程。 */
    void track(final ConsumerRecords<String, byte[]> records, final CompletionStage<Void> completion) {
        if (pending != null) {
            throw KafkaRpcResourceException.create("kafka rpc batch already pending");
        }
        for (ConsumerRecord<String, byte[]> record : records) {
            offsets.put(new TopicPartition(record.topic(), record.partition()),
                    new OffsetAndMetadata(record.offset() + 1));
        }
        pending = completion.toCompletableFuture();
        consumer.pause(consumer.assignment());
    }

    /** 仅提交全部成功的批次；异常向上抛出并使 worker 重建 consumer，从未提交处恢复。 */
    void commitCompleted() {
        if (invalidated) {
            throw KafkaRpcResourceException.create("kafka rpc batch ownership changed");
        }
        if (pending == null || !pending.isDone()) {
            return;
        }
        pending.join();
        consumer.commitSync(Map.copyOf(offsets));
        pending = null;
        offsets.clear();
        consumer.resume(consumer.assignment());
    }

    /** 分区撤销时放弃当前确认，避免旧 owner 提交新 owner 的消息；不等待业务阶段。 */
    @Override
    public void onPartitionsRevoked(final Collection<TopicPartition> partitions) {
        if (pending != null) {
            invalidated = true;
        }
    }

    /** 新分配的分区重新开始消费，调用在 consumer 线程执行。 */
    @Override
    public void onPartitionsAssigned(final Collection<TopicPartition> partitions) {
        consumer.resume(partitions);
    }
}
