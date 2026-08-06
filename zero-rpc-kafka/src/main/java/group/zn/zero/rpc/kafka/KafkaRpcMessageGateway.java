package group.zn.zero.rpc.kafka;

import java.util.concurrent.CompletionStage;

/**
 * Kafka RPC 消息网关。
 *
 * @author zn
 */
interface KafkaRpcMessageGateway extends AutoCloseable {

    /**
     * 发送 Kafka RPC 消息。
     *
     * @param message Kafka RPC 消息；不可为空。
     * @return 发送完成阶段；不可为空；线程安全性由实现声明。
     */
    CompletionStage<Void> send(KafkaRpcMessage message);

    /**
     * 订阅 topic 消息。
     *
     * @param topic Kafka topic；不可为空。
     * @param listener 消息监听器；不可为空。
     * @throws RuntimeException 当订阅失败时抛出，必须绑定或转入统一错误处理。
     */
    void subscribe(String topic, KafkaRpcMessageListener listener);

    /**
     * 按指定 consumer group 订阅 topic 消息。
     *
     * @param topic Kafka topic；不可为空。
     * @param group Kafka consumer group；可为空，具体实现可回退到默认 group。
     * @param listener 消息监听器；不可为空。
     * @throws RuntimeException 当订阅失败时抛出，必须绑定或转入统一错误处理。
     */
    default void subscribe(final String topic, final String group, final KafkaRpcMessageListener listener) {
        subscribe(topic, listener);
    }

    /**
     * 取消订阅 topic 消息。
     *
     * @param topic Kafka topic；不可为空。
     * @param listener 消息监听器；不可为空。
     * @throws RuntimeException 当取消订阅失败时抛出，必须绑定或转入统一错误处理。
     */
    void unsubscribe(String topic, KafkaRpcMessageListener listener);

    /**
     * 按指定 consumer group 取消订阅 topic 消息。
     *
     * @param topic Kafka topic；不可为空。
     * @param group Kafka consumer group；可为空，具体实现可回退到默认 group。
     * @param listener 消息监听器；不可为空。
     * @throws RuntimeException 当取消订阅失败时抛出，必须绑定或转入统一错误处理。
     */
    default void unsubscribe(final String topic, final String group, final KafkaRpcMessageListener listener) {
        unsubscribe(topic, listener);
    }

    /**
     * 关闭消息网关。
     *
     * @throws RuntimeException 当关闭失败时抛出，必须绑定或转入统一错误处理。
     */
    @Override
    void close();
}
