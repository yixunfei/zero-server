package group.zn.zero.rpc.kafka;

import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import java.util.Objects;

/**
 * Kafka RPC 传输 envelope。
 *
 * @param kind 消息类型。
 * @param request RPC 请求；仅 request 消息不为空。
 * @param response RPC 响应；仅 response 消息不为空。
 * @author zn
 */
record KafkaRpcEnvelope(KafkaRpcEnvelopeKind kind, RpcRequest request, RpcResponse response) {

    /**
     * 创建 Kafka RPC 传输 envelope。
     *
     * @throws NullPointerException 当消息类型为空时抛出。
     * @throws IllegalArgumentException 当消息类型与请求/响应字段不匹配时抛出。
     */
    KafkaRpcEnvelope {
        Objects.requireNonNull(kind, "kind");
        if (kind == KafkaRpcEnvelopeKind.REQUEST && request == null) {
            throw new IllegalArgumentException("request envelope must contain request");
        }
        if (kind == KafkaRpcEnvelopeKind.RESPONSE && response == null) {
            throw new IllegalArgumentException("response envelope must contain response");
        }
    }

    /**
     * 创建请求 envelope。
     *
     * @param request RPC 请求；不可为空。
     * @return 请求 envelope；不可为空；线程安全。
     */
    static KafkaRpcEnvelope request(final RpcRequest request) {
        return new KafkaRpcEnvelope(KafkaRpcEnvelopeKind.REQUEST, Objects.requireNonNull(request, "request"), null);
    }

    /**
     * 创建响应 envelope。
     *
     * @param response RPC 响应；不可为空。
     * @return 响应 envelope；不可为空；线程安全。
     */
    static KafkaRpcEnvelope response(final RpcResponse response) {
        return new KafkaRpcEnvelope(KafkaRpcEnvelopeKind.RESPONSE, null, Objects.requireNonNull(response, "response"));
    }
}
