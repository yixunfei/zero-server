package group.zn.zero.rpc.kafka;

/**
 * Kafka RPC 消息监听器。
 *
 * @author zn
 */
@FunctionalInterface
interface KafkaRpcMessageListener {

    /**
     * 处理 Kafka RPC 模块内部消息。
     *
     * @param message Kafka RPC 消息；不可为空。
     * @throws RuntimeException 当消息处理失败时抛出，调用方必须进入统一错误处理。
     */
    void onMessage(KafkaRpcMessage message);
}
