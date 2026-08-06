package group.zn.zero.actor.remote;

import group.zn.zero.actor.LaneKey;
import java.util.Map;
import java.util.Objects;

/**
 * Actor 投递路由。
 *
 * @param kind 路由类型。
 * @param address Actor 地址。
 * @param methodName 远程方法名；本地路由为空字符串。
 * @param transport 远程传输名称；本地路由为空字符串。
 * @param requestTopic 远程 request topic；可为空字符串。
 * @param consumerGroup 远程 consumer group；可为空字符串。
 * @param partitionKey 远程分区键；可为空字符串。
 * @param attributes 扩展属性；不可为空；不可变、无序、可能为空、线程安全。
 * @author zn
 */
public record ActorRoute(
        ActorRouteKind kind,
        ActorAddress address,
        String methodName,
        String transport,
        String requestTopic,
        String consumerGroup,
        String partitionKey,
        Map<String, String> attributes) {

    /**
     * 创建 Actor 投递路由。
     *
     * @throws NullPointerException 当路由类型、地址或扩展属性为空时抛出。
     * @throws IllegalArgumentException 当远程路由字段非法时抛出。
     */
    public ActorRoute {
        kind = Objects.requireNonNull(kind, "kind");
        address = Objects.requireNonNull(address, "address");
        methodName = valueOrEmpty(methodName, "methodName");
        transport = valueOrEmpty(transport, "transport");
        requestTopic = valueOrEmpty(requestTopic, "requestTopic");
        consumerGroup = valueOrEmpty(consumerGroup, "consumerGroup");
        partitionKey = valueOrEmpty(partitionKey, "partitionKey");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        if (kind == ActorRouteKind.REMOTE) {
            requireRemote(address, methodName, transport);
        }
    }

    /**
     * 创建本地 Actor 路由。
     *
     * @param laneKey lane key；不可为空。
     * @return 本地路由；不可为空；线程安全。
     */
    public static ActorRoute local(final LaneKey laneKey) {
        return new ActorRoute(
                ActorRouteKind.LOCAL,
                ActorAddress.local(laneKey),
                "",
                "",
                "",
                "",
                "",
                Map.of());
    }

    /**
     * 创建远程 Actor 路由。
     *
     * @param address 远程 Actor 地址；不可为空。
     * @param methodName 远程方法名；不可为空白。
     * @param transport 远程传输名称；不可为空白。
     * @param requestTopic request topic；可为空。
     * @param consumerGroup consumer group；可为空。
     * @param partitionKey 分区键；可为空。
     * @param attributes 扩展属性；不可为空。
     * @return 远程路由；不可为空；线程安全。
     */
    public static ActorRoute remote(
            final ActorAddress address,
            final String methodName,
            final String transport,
            final String requestTopic,
            final String consumerGroup,
            final String partitionKey,
            final Map<String, String> attributes) {
        return new ActorRoute(
                ActorRouteKind.REMOTE,
                address,
                methodName,
                transport,
                requestTopic,
                consumerGroup,
                partitionKey,
                attributes);
    }

    /**
     * 返回路由绑定的 lane key。
     *
     * @return lane key；不可为空；线程安全。
     */
    public LaneKey laneKey() {
        return address.laneKey();
    }

    private static void requireRemote(
            final ActorAddress address,
            final String methodName,
            final String transport) {
        if (!address.remote()) {
            throw new IllegalArgumentException("remote actor route requires remote address");
        }
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("remote actor route methodName must not be blank");
        }
        if (transport.isBlank()) {
            throw new IllegalArgumentException("remote actor route transport must not be blank");
        }
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
