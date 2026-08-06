package group.zn.zero.rpc.kafka;

import java.util.Objects;

/**
 * Kafka RPC 模块内部消息。
 *
 * @param topic Kafka topic。
 * @param key Kafka key。
 * @param value Kafka value 字节。
 * @author zn
 */
record KafkaRpcMessage(String topic, String key, byte[] value) {

    /**
     * 创建 Kafka RPC 模块内部消息。
     *
     * @throws NullPointerException 当 topic、key 或 value 为空时抛出。
     */
    KafkaRpcMessage {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        value = value.clone();
    }

    /**
     * 返回 Kafka value 副本。
     *
     * @return 可变字节数组副本；可能为空；无序；线程安全。
     */
    @Override
    public byte[] value() {
        return value.clone();
    }
}
