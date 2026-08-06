package group.zn.zero.rpc.kafka;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;
import java.util.Objects;

/**
 * Kafka RPC 远端错误码副本。
 *
 * @param category 错误分类。
 * @param code 错误码。
 * @param message 错误说明。
 * @author zn
 */
public record KafkaRpcWireErrorCode(ErrorCategory category, String code, String message) implements ErrorCode {

    /**
     * 创建远端错误码副本。
     *
     * @throws NullPointerException 当错误分类、错误码或错误说明为空时抛出。
     */
    public KafkaRpcWireErrorCode {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
