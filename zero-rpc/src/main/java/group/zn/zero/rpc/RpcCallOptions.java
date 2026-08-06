package group.zn.zero.rpc;

import java.util.Objects;

/**
 * RPC 单次调用选项。
 *
 * @author zn
 */
public final class RpcCallOptions {

    /**
     * 默认 reply topic。
     */
    private static final String DEFAULT_REPLY_TOPIC = "local-reply";

    /**
     * 默认 TraceId。
     */
    private static final String DEFAULT_TRACE_ID = "local-trace";

    /**
     * 响应 topic。
     */
    private final String replyTopic;

    /**
     * 链路追踪 ID。
     */
    private final String traceId;

    /**
     * 覆盖方法默认超时的毫秒数。
     */
    private final long timeoutMillis;

    /**
     * 创建 RPC 单次调用选项。
     *
     * @param replyTopic 响应 topic；不可为空。
     * @param traceId 链路追踪 ID；不可为空。
     * @param timeoutMillis 超时毫秒数；0 表示使用方法默认超时。
     * @throws NullPointerException 当响应 topic 或 TraceId 为空时抛出。
     * @throws IllegalArgumentException 当超时毫秒数为负数时抛出。
     */
    public RpcCallOptions(final String replyTopic, final String traceId, final long timeoutMillis) {
        this.replyTopic = Objects.requireNonNull(replyTopic, "replyTopic");
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        if (timeoutMillis < 0L) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 返回默认调用选项。
     *
     * @return 默认调用选项；不可为空；线程安全。
     */
    public static RpcCallOptions defaults() {
        return new RpcCallOptions(DEFAULT_REPLY_TOPIC, DEFAULT_TRACE_ID, 0L);
    }

    /**
     * 基于当前选项替换 TraceId。
     *
     * @param value TraceId；不可为空。
     * @return 新调用选项；不可为空；线程安全。
     */
    public RpcCallOptions withTraceId(final String value) {
        return new RpcCallOptions(replyTopic, value, timeoutMillis);
    }

    /**
     * 基于当前选项替换 reply topic。
     *
     * @param value reply topic；不可为空。
     * @return 新调用选项；不可为空；线程安全。
     */
    public RpcCallOptions withReplyTopic(final String value) {
        return new RpcCallOptions(value, traceId, timeoutMillis);
    }

    /**
     * 基于当前选项替换超时时间。
     *
     * @param value 超时毫秒数；0 表示使用方法默认超时。
     * @return 新调用选项；不可为空；线程安全。
     */
    public RpcCallOptions withTimeoutMillis(final long value) {
        return new RpcCallOptions(replyTopic, traceId, value);
    }

    /**
     * 返回响应 topic。
     *
     * @return 响应 topic；不可为空；线程安全。
     */
    public String replyTopic() {
        return replyTopic;
    }

    /**
     * 返回链路追踪 ID。
     *
     * @return 链路追踪 ID；不可为空；线程安全。
     */
    public String traceId() {
        return traceId;
    }

    /**
     * 返回配置的超时毫秒数。
     *
     * @return 超时毫秒数；0 表示使用方法默认超时；线程安全。
     */
    public long timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * 解析本次调用最终超时时间。
     *
     * @param defaultTimeoutMillis 方法默认超时时间；必须大于 0。
     * @return 最终超时时间，单位毫秒；线程安全。
     * @throws IllegalArgumentException 当默认超时时间非法时抛出。
     */
    public long resolveTimeoutMillis(final long defaultTimeoutMillis) {
        if (defaultTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("defaultTimeoutMillis must be positive");
        }
        return timeoutMillis > 0L ? timeoutMillis : defaultTimeoutMillis;
    }
}
