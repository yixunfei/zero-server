package group.zn.zero.rpc.kafka;

/**
 * Kafka RPC envelope 类型。
 *
 * @author zn
 */
enum KafkaRpcEnvelopeKind {

    /**
     * 请求消息。
     */
    REQUEST((byte) 1),

    /**
     * 响应消息。
     */
    RESPONSE((byte) 2);

    /**
     * 线格式类型码。
     */
    private final byte wireCode;

    KafkaRpcEnvelopeKind(final byte wireCode) {
        this.wireCode = wireCode;
    }

    /**
     * 返回线格式类型码。
     *
     * @return 类型码；线程安全。
     */
    byte wireCode() {
        return wireCode;
    }

    /**
     * 根据线格式类型码解析消息类型。
     *
     * @param wireCode 类型码。
     * @return 消息类型；不可为空；线程安全。
     * @throws IllegalArgumentException 当类型码未知时抛出。
     */
    static KafkaRpcEnvelopeKind fromWireCode(final byte wireCode) {
        for (KafkaRpcEnvelopeKind kind : values()) {
            if (kind.wireCode == wireCode) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown kafka rpc envelope kind: " + wireCode);
    }
}
