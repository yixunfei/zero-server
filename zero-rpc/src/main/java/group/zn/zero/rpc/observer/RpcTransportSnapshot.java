package group.zn.zero.rpc.observer;

import java.util.Map;
import java.util.Objects;

/**
 * RPC 传输只读状态快照。
 *
 * @param transportName 传输名称。
 * @param closed 是否已关闭。
 * @param pendingSize 当前 pending 数量。
 * @param pendingCapacity pending 容量。
 * @param requestSentCount request/response 发送成功次数。
 * @param onewaySentCount oneway 发送成功次数。
 * @param requestReceivedCount 收到请求次数。
 * @param requestRejectedCount 消费端拒绝请求次数。
 * @param responseSentCount 响应发送成功次数。
 * @param sendFailureCount 发送失败次数。
 * @param pendingRejectedCount pending 拒绝次数。
 * @param pendingTimeoutCount pending 超时次数。
 * @param consumerRestartCount consumer 重启尝试次数。
 * @param attributes 扩展属性；不可为空；不可变、无序、可能为空、线程安全。
 * @author zn
 */
public record RpcTransportSnapshot(
        String transportName,
        boolean closed,
        int pendingSize,
        int pendingCapacity,
        long requestSentCount,
        long onewaySentCount,
        long requestReceivedCount,
        long requestRejectedCount,
        long responseSentCount,
        long sendFailureCount,
        long pendingRejectedCount,
        long pendingTimeoutCount,
        long consumerRestartCount,
        Map<String, String> attributes) {

    /**
     * 创建 RPC 传输状态快照。
     *
     * @throws NullPointerException 当传输名称或扩展属性为空时抛出。
     * @throws IllegalArgumentException 当 pending 数量或容量为负数时抛出。
     */
    public RpcTransportSnapshot {
        transportName = requireText(transportName, "transportName");
        if (pendingSize < 0) {
            throw new IllegalArgumentException("pendingSize must be non-negative");
        }
        if (pendingCapacity < 0) {
            throw new IllegalArgumentException("pendingCapacity must be non-negative");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
