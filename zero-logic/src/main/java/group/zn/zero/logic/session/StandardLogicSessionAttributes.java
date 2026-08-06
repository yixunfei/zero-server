package group.zn.zero.logic.session;

/**
 * 标准业务会话属性。
 *
 * @author zn
 */
public final class StandardLogicSessionAttributes {

    /**
     * 已处理请求数量。
     */
    public static final LogicSessionAttributeKey<Long> REQUEST_COUNT =
            LogicSessionAttributeKey.of("requestCount", Long.class);

    /**
     * 最近一次请求时间，毫秒时间戳。
     */
    public static final LogicSessionAttributeKey<Long> LAST_REQUEST_TIME_MILLIS =
            LogicSessionAttributeKey.of("lastRequestTimeMillis", Long.class);

    /**
     * 最近两次请求间隔，毫秒。
     */
    public static final LogicSessionAttributeKey<Long> LAST_REQUEST_INTERVAL_MILLIS =
            LogicSessionAttributeKey.of("lastRequestIntervalMillis", Long.class);

    /**
     * 渠道编码。
     */
    public static final LogicSessionAttributeKey<String> CHANNEL_CODE =
            LogicSessionAttributeKey.of("channelCode", String.class);

    private StandardLogicSessionAttributes() {
    }
}
