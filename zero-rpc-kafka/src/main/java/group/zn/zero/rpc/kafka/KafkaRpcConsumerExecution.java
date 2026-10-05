package group.zn.zero.rpc.kafka;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Kafka consumer 将已拉取消息交付给应用监听器时使用的执行策略。
 *
 * <p>该策略只决定单个 consumer 内的业务交付顺序；Kafka poll、提交和分区暂停始终由 consumer
 * 线程独占。默认策略保持并发，按分区顺序必须由调用方显式选择。</p>
 *
 * @param executor 应用监听器执行器；不可为空，必须不能在提交任务的 poll 线程内联执行。
 * @param preservePartitionOrder 是否在同一 topic partition 内按 offset 顺序开始处理。
 * @author zn
 */
public record KafkaRpcConsumerExecution(Executor executor, boolean preservePartitionOrder) {

    /**
     * 创建 Kafka consumer 业务并发执行策略。
     *
     * @param executor 应用监听器执行器；不可为空。
     * @return 并发策略；不可为空；线程安全。
     */
    public static KafkaRpcConsumerExecution concurrent(final Executor executor) {
        return new KafkaRpcConsumerExecution(executor, false);
    }

    /**
     * 创建同一 Kafka 分区内按 offset 串行开始处理的策略。
     *
     * <p>该策略会让慢消息阻塞同一分区后续消息，但不同分区仍可并发处理。</p>
     *
     * @param executor 应用监听器执行器；不可为空。
     * @return 分区有序策略；不可为空；线程安全。
     */
    public static KafkaRpcConsumerExecution partitionOrdered(final Executor executor) {
        return new KafkaRpcConsumerExecution(executor, true);
    }

    /**
     * 创建策略。
     *
     * @throws NullPointerException 当执行器为空时抛出。
     */
    public KafkaRpcConsumerExecution {
        Objects.requireNonNull(executor, "executor");
    }

    /**
     * 返回保留给旧构造器的异步并发策略。
     *
     * <p>生产 starter 不使用该策略，必须注入其受管 remote-IO 执行器。该兼容路径仅保证 listener
     * 不会在 Kafka poll 线程内同步执行。</p>
     *
     * @return 异步并发策略；不可为空；线程安全。
     */
    public static KafkaRpcConsumerExecution compatibilityDefault() {
        return concurrent(ForkJoinPool.commonPool());
    }
}
