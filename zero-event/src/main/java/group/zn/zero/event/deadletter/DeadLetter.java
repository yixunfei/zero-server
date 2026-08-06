package group.zn.zero.event.deadletter;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.event.ZeroEvent;
import java.time.Instant;

/**
 * 事件死信记录。
 *
 * @param event 原始事件。
 * @param errorCode 错误码。
 * @param reason 异常说明。
 * @param retryCount 已重试次数。
 * @param occurredAt 发生时间。
 * @author zn
 */
public record DeadLetter(
        ZeroEvent event,
        ErrorCode errorCode,
        String reason,
        int retryCount,
        Instant occurredAt) {

    /**
     * 创建事件死信记录。
     *
     * @throws NullPointerException 当事件、错误码、说明或发生时间为空时抛出。
     */
    public DeadLetter {
        java.util.Objects.requireNonNull(event, "event");
        java.util.Objects.requireNonNull(errorCode, "errorCode");
        java.util.Objects.requireNonNull(reason, "reason");
        java.util.Objects.requireNonNull(occurredAt, "occurredAt");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retryCount must not be negative");
        }
    }
}
