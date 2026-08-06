package group.zn.zero.rpc.kafka;

import group.zn.zero.rpc.RpcRequest;
import java.util.Objects;

/**
 * Kafka RPC topic 解析器。
 *
 * @author zn
 */
public final class KafkaRpcTopicResolver {

    /**
     * topic 前缀。
     */
    private final String topicPrefix;

    /**
     * 创建 Kafka RPC topic 解析器。
     *
     * @param topicPrefix topic 前缀；不可为空。
     * @throws NullPointerException 当 topic 前缀为空时抛出。
     * @throws IllegalArgumentException 当 topic 前缀为空白时抛出。
     */
    public KafkaRpcTopicResolver(final String topicPrefix) {
        this.topicPrefix = Objects.requireNonNull(topicPrefix, "topicPrefix");
        if (topicPrefix.isBlank()) {
            throw new IllegalArgumentException("topicPrefix must not be blank");
        }
    }

    /**
     * 根据 RPC 请求解析 request topic。
     *
     * @param request RPC 请求；不可为空。
     * @return request topic；不可为空；线程安全。
     * @throws NullPointerException 当请求为空时抛出。
     */
    public String requestTopic(final RpcRequest request) {
        RpcRequest current = Objects.requireNonNull(request, "request");
        return current.topic().isBlank() ? requestTopic(current.serviceName()) : current.topic();
    }

    /**
     * 根据服务名解析 request topic。
     *
     * @param serviceName 路由服务名；不可为空。
     * @return request topic；不可为空；线程安全。
     * @throws NullPointerException 当服务名为空时抛出。
     */
    public String requestTopic(final String serviceName) {
        String normalized = sanitize(serviceName);
        return topicPrefix + ".request." + normalized;
    }

    /**
     * 清理 Kafka topic 片段。
     *
     * @param value topic 片段；不可为空。
     * @return 可用于 Kafka topic 的片段；不可为空；线程安全。
     * @throws NullPointerException 当 topic 片段为空时抛出。
     */
    public String sanitize(final String value) {
        String source = Objects.requireNonNull(value, "value").trim();
        StringBuilder builder = new StringBuilder(source.length());
        boolean previousDot = false;
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            boolean valid = ch >= 'a' && ch <= 'z'
                    || ch >= 'A' && ch <= 'Z'
                    || ch >= '0' && ch <= '9'
                    || ch == '_'
                    || ch == '-';
            if (valid) {
                builder.append(ch);
                previousDot = false;
            } else if (!previousDot) {
                builder.append('.');
                previousDot = true;
            }
        }
        String result = builder.toString();
        while (result.startsWith(".")) {
            result = result.substring(1);
        }
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isBlank() ? "unknown" : result;
    }
}
