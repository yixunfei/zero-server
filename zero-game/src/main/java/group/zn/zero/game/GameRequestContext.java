package group.zn.zero.game;

import java.util.Objects;

/**
 * 游戏业务请求上下文。
 *
 * <p>该上下文承载 traceId 和入口来源，供业务 API、Actor 消息、日志与指标传递。
 * record 本身不可变且线程安全；不会修改业务状态。
 *
 * @param traceId 链路追踪 ID。
 * @param source 请求来源。
 * @author zn
 */
public record GameRequestContext(String traceId, String source) {

    /**
     * 创建游戏业务请求上下文。
     *
     * @throws NullPointerException 当 traceId 或 source 为空时抛出。
     * @throws IllegalArgumentException 当 traceId 或 source 为空白时抛出。
     */
    public GameRequestContext {
        traceId = requireText(traceId, "traceId");
        source = requireText(source, "source");
    }

    /**
     * 创建客户端协议请求上下文。
     *
     * @param traceId 链路追踪 ID；不可为空。
     * @return 请求上下文；不可为空；线程安全。
     */
    public static GameRequestContext client(final String traceId) {
        return new GameRequestContext(traceId, "client");
    }

    /**
     * 创建 GM 请求上下文。
     *
     * @param traceId 链路追踪 ID；不可为空。
     * @return 请求上下文；不可为空；线程安全。
     */
    public static GameRequestContext gm(final String traceId) {
        return new GameRequestContext(traceId, "gm");
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
