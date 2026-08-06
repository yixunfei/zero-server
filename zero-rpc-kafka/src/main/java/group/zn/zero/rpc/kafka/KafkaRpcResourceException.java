package group.zn.zero.rpc.kafka;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.error.RpcErrorCode;

/**
 * Kafka RPC 资源阶段的安全异常。
 *
 * <p>该异常只保存框架定义的固定消息，不保留第三方异常 message、cause 或 suppressed 图。
 * cleanup 失败也必须先转换为本类型，才能挂到主失败的 suppressed 列表。</p>
 *
 * @author zn
 */
final class KafkaRpcResourceException extends ZeroException {

    /**
     * 创建安全资源异常。
     *
     * @param safeMessage 固定安全消息；不可为空。
     */
    private KafkaRpcResourceException(final String safeMessage) {
        super(RpcErrorCode.TRANSPORT_UNAVAILABLE, safeMessage);
    }

    /**
     * 将任意非受检失败转换为不携带第三方异常图的安全异常。
     *
     * @param safeMessage 固定安全消息；不可为空。
     * @param failure 原失败；不可为空，只用于判断是否已完成安全转换。
     * @return 安全异常；不可为空；非线程安全。
     */
    static KafkaRpcResourceException sanitize(
            final String safeMessage,
            final Throwable failure) {
        if (failure instanceof KafkaRpcResourceException safeFailure) {
            return safeFailure;
        }
        return new KafkaRpcResourceException(safeMessage);
    }

    /**
     * 将 cleanup 失败安全地合并到首个失败。
     *
     * @param primary 首个安全失败；可为空。
     * @param failure 当前失败；不可为空。
     * @param safeMessage 当前阶段的固定安全消息；不可为空。
     * @return 首个安全失败；不可为空；非线程安全。
     */
    static KafkaRpcResourceException merge(
            final KafkaRpcResourceException primary,
            final Throwable failure,
            final String safeMessage) {
        KafkaRpcResourceException safeFailure = sanitize(safeMessage, failure);
        if (primary == null) {
            return safeFailure;
        }
        if (primary != safeFailure) {
            primary.addSuppressed(safeFailure);
        }
        return primary;
    }

    /**
     * 创建一个固定消息的安全异常。
     *
     * @param safeMessage 固定安全消息；不可为空。
     * @return 安全异常；不可为空；线程安全。
     */
    static KafkaRpcResourceException create(final String safeMessage) {
        return new KafkaRpcResourceException(safeMessage);
    }
}
