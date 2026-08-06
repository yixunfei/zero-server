package group.zn.zero.actor.remote;

import java.time.Duration;
import java.util.Objects;

/**
 * Actor 投递选项。
 *
 * <p>第一版远程状态修改消息默认不自动重试。调用方可以显式携带幂等键，供后续远程
 * gateway、日志和业务处理器识别重复投递风险。
 *
 * @param timeout 投递超时时间。
 * @param retryEnabled 是否允许框架自动重试。
 * @param replyTopic RPC 桥接使用的响应 topic；oneway 场景仍需填充非空占位。
 * @param idempotencyKey 幂等键；可为空字符串。
 * @author zn
 */
public record ActorDispatchOptions(
        Duration timeout,
        boolean retryEnabled,
        String replyTopic,
        String idempotencyKey) {

    /**
     * 默认超时时间。
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 默认 reply topic。
     */
    public static final String DEFAULT_REPLY_TOPIC = "actor-reply";

    /**
     * 创建 Actor 投递选项。
     *
     * @throws NullPointerException 当超时时间或 reply topic 为空时抛出。
     * @throws IllegalArgumentException 当超时时间非正数或文本字段非法时抛出。
     */
    public ActorDispatchOptions {
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        replyTopic = requireText(replyTopic, "replyTopic");
        idempotencyKey = valueOrEmpty(idempotencyKey, "idempotencyKey");
    }

    /**
     * 返回默认投递选项。
     *
     * @return 默认选项；不可为空；线程安全。
     */
    public static ActorDispatchOptions defaults() {
        return new ActorDispatchOptions(DEFAULT_TIMEOUT, false, DEFAULT_REPLY_TOPIC, "");
    }

    /**
     * 返回替换超时时间的新选项。
     *
     * @param value 超时时间；不可为空；必须为正数。
     * @return 新选项；不可为空；线程安全。
     */
    public ActorDispatchOptions withTimeout(final Duration value) {
        return new ActorDispatchOptions(value, retryEnabled, replyTopic, idempotencyKey);
    }

    /**
     * 返回替换重试开关的新选项。
     *
     * @param value true 表示允许框架自动重试。
     * @return 新选项；不可为空；线程安全。
     */
    public ActorDispatchOptions withRetryEnabled(final boolean value) {
        return new ActorDispatchOptions(timeout, value, replyTopic, idempotencyKey);
    }

    /**
     * 返回替换 reply topic 的新选项。
     *
     * @param value reply topic；不可为空白。
     * @return 新选项；不可为空；线程安全。
     */
    public ActorDispatchOptions withReplyTopic(final String value) {
        return new ActorDispatchOptions(timeout, retryEnabled, value, idempotencyKey);
    }

    /**
     * 返回替换幂等键的新选项。
     *
     * @param value 幂等键；可为空。
     * @return 新选项；不可为空；线程安全。
     */
    public ActorDispatchOptions withIdempotencyKey(final String value) {
        return new ActorDispatchOptions(timeout, retryEnabled, replyTopic, value);
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    private static String valueOrEmpty(final String value, final String name) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
