package group.zn.zero.rpc;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认 RPC correlationId 生成器。
 *
 * <p>生成器在构造阶段创建节点前缀，调用热路径只执行一次原子递增和字符串拼接，避免
 * {@code UUID.randomUUID()} 在高并发下的随机数成本。生成结果格式为：</p>
 *
 * <pre>
 * 节点ID-时间戳片段-随机片段-递增序列
 * </pre>
 *
 * @author zn
 */
public final class DefaultRpcCorrelationIdGenerator implements RpcCorrelationIdGenerator {

    /**
     * 默认节点 ID。
     */
    private static final String DEFAULT_NODE_ID = "rpc";

    /**
     * ID 分隔符。
     */
    private static final char SEPARATOR = '-';

    /**
     * correlationId 前缀。
     */
    private final String prefix;

    /**
     * 进程内递增序列。
     */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 创建默认生成器。
     */
    public DefaultRpcCorrelationIdGenerator() {
        this(DEFAULT_NODE_ID);
    }

    /**
     * 创建指定节点 ID 的默认生成器。
     *
     * @param nodeId 节点 ID；不可为空，空白时使用默认节点 ID。
     * @throws NullPointerException 当节点 ID 为空时抛出。
     */
    public DefaultRpcCorrelationIdGenerator(final String nodeId) {
        String normalizedNodeId = normalizeNodeId(Objects.requireNonNull(nodeId, "nodeId"));
        long startedAt = System.currentTimeMillis();
        long random = ThreadLocalRandom.current().nextLong();
        this.prefix = normalizedNodeId
                + SEPARATOR
                + Long.toUnsignedString(startedAt, Character.MAX_RADIX)
                + SEPARATOR
                + Long.toUnsignedString(random, Character.MAX_RADIX);
    }

    /**
     * 生成下一个 correlationId。
     *
     * @return correlationId；不可为空；同一生成器实例内不重复；线程安全。
     */
    @Override
    public String nextCorrelationId() {
        long value = sequence.incrementAndGet();
        return prefix + SEPARATOR + Long.toUnsignedString(value, Character.MAX_RADIX);
    }

    private static String normalizeNodeId(final String nodeId) {
        String trimmed = nodeId.trim();
        if (trimmed.isBlank()) {
            return DEFAULT_NODE_ID;
        }
        StringBuilder builder = new StringBuilder(trimmed.length());
        for (int index = 0; index < trimmed.length(); index++) {
            char value = trimmed.charAt(index);
            if (Character.isLetterOrDigit(value) || value == '-' || value == '_') {
                builder.append(value);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
