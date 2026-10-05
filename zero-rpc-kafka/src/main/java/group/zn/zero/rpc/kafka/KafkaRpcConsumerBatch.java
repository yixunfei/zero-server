package group.zn.zero.rpc.kafka;

import java.util.Collection;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
    /** 分区级在途处理状态；所有结构访问仅由 consumer poll 线程执行。 */
    private final Map<TopicPartition, PendingPartition> pending = new HashMap<>();
    /** 有在途批次时发生重平衡，必须重建 consumer 才能恢复保留分区的读取位置。 */
    private boolean invalidated;

    /** 创建线程封闭的批次确认器。 */
    KafkaRpcConsumerBatch(final Consumer<String, byte[]> consumer) {
        this.consumer = consumer;
    }

    /**
     * 记录实际取到的消息与完成信号，并暂停本次涉及的分区，调用方必须是 consumer 线程。
     *
     * <p>该兼容重载把一个完成阶段关联到全部分区；生产 worker 使用分区阶段重载，以免一条慢消息
     * 暂停无关分区。</p>
     */
    void track(final ConsumerRecords<String, byte[]> records, final CompletionStage<Void> completion) {
        Map<TopicPartition, CompletionStage<Void>> completions = new HashMap<>();
        for (TopicPartition partition : records.partitions()) {
            completions.put(partition, completion);
        }
        track(records, completions);
    }

    /**
     * 记录每个分区的完成阶段；调用方必须在 consumer poll 线程调用。
     *
     * @param records 本次 poll 结果；不可为空。
     * @param completions 分区处理完成阶段；每个包含记录的分区必须有一个阶段。
     */
    void track(
            final ConsumerRecords<String, byte[]> records,
            final Map<TopicPartition, ? extends CompletionStage<Void>> completions) {
        Set<TopicPartition> toPause = new HashSet<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            if (pending.containsKey(partition)) {
                throw KafkaRpcResourceException.create("kafka rpc partition already pending");
            }
            toPause.add(partition);
        }
        for (TopicPartition partition : toPause) {
            CompletionStage<Void> completion = completions.get(partition);
            if (completion == null) {
                throw KafkaRpcResourceException.create("kafka rpc partition completion missing");
            }
            PendingPartition current = new PendingPartition(offsetAfter(records, partition));
            pending.put(partition, current);
            try {
                completion.whenComplete((ignored, failure) -> current.complete(failure));
            } catch (RuntimeException | Error failure) {
                current.complete(failure);
            }
        }
        if (!toPause.isEmpty()) {
            consumer.pause(toPause);
        }
    }

    /**
     * 使用几乎无限的处理预算推进完成确认；仅供兼容测试调用。
     */
    void commitCompleted() {
        commitCompleted(Duration.ofDays(365));
    }

    /**
     * 提交已完成的分区，超过预算或出现失败时中止 worker 以便从未提交 offset 重建。
     *
     * <p>本方法永不等待用户完成阶段，因而 poll 线程始终可以继续发出心跳和处理未暂停分区。</p>
     *
     * @param processingBudget 从 dispatch 到完成的最大预算；必须为正。
     */
    void commitCompleted(final Duration processingBudget) {
        if (processingBudget.isNegative() || processingBudget.isZero()) {
            throw new IllegalArgumentException("processingBudget must be positive");
        }
        if (invalidated) {
            throw KafkaRpcResourceException.create("kafka rpc batch ownership changed");
        }
        if (pending.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        Map<TopicPartition, OffsetAndMetadata> completedOffsets = new HashMap<>();
        for (Map.Entry<TopicPartition, PendingPartition> entry : pending.entrySet()) {
            PendingPartition current = entry.getValue();
            CompletionOutcome outcome = current.outcome;
            if (outcome != null && outcome.failure() != null) {
                throw KafkaRpcResourceException.create("kafka rpc listener failed");
            }
            if (outcome != null) {
                completedOffsets.put(entry.getKey(), current.offset);
            } else if (current.exceededBudget(now, processingBudget)) {
                throw KafkaRpcResourceException.create("kafka rpc listener processing budget exceeded");
            }
        }
        if (completedOffsets.isEmpty()) {
            return;
        }
        consumer.commitSync(Map.copyOf(completedOffsets));
        completedOffsets.keySet().forEach(pending::remove);
        consumer.resume(completedOffsets.keySet());
    }

    /** 分区撤销时放弃当前确认，避免旧 owner 提交新 owner 的消息；不等待业务阶段。 */
    @Override
    public void onPartitionsRevoked(final Collection<TopicPartition> partitions) {
        if (partitions.stream().anyMatch(pending::containsKey)) {
            invalidated = true;
        }
    }

    /** 新分配的分区重新开始消费，调用在 consumer 线程执行。 */
    @Override
    public void onPartitionsAssigned(final Collection<TopicPartition> partitions) {
        consumer.resume(partitions);
    }

    /** 返回本次 poll 中指定分区最后一条记录之后的提交 offset。 */
    private OffsetAndMetadata offsetAfter(
            final ConsumerRecords<String, byte[]> records, final TopicPartition partition) {
        long offset = -1L;
        for (ConsumerRecord<String, byte[]> record : records.records(partition)) {
            offset = record.offset() + 1L;
        }
        if (offset < 0L) {
            throw KafkaRpcResourceException.create("kafka rpc partition record missing");
        }
        return new OffsetAndMetadata(offset);
    }

    /** 单个 partition 的异步处理状态；阶段回调可由任意线程完成。 */
    private static final class PendingPartition {
        /** 该分区可提交的 offset 上界。 */
        private final OffsetAndMetadata offset;
        /** dispatch 时的单调时间，避免墙钟回拨延长预算。 */
        private final long startedAtNanos = System.nanoTime();
        /** 处理终态；单次 volatile 发布同时发布成功/失败，避免两个字段读取竞态。 */
        private volatile CompletionOutcome outcome;

        /** 创建分区状态。 */
        private PendingPartition(final OffsetAndMetadata offset) {
            this.offset = offset;
        }

        /** 由完成回调发布最终状态。 */
        private void complete(final Throwable currentFailure) {
            outcome = new CompletionOutcome(currentFailure);
        }

        /** 判断是否已超过指定单调时间预算。 */
        private boolean exceededBudget(final long now, final Duration budget) {
            return now - startedAtNanos >= budget.toNanos();
        }
    }

    /** 单次发布的业务处理结果；failure 为空表示成功。 @author zn */
    private record CompletionOutcome(Throwable failure) { }
}
