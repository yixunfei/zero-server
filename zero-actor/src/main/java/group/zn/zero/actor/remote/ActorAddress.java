package group.zn.zero.actor.remote;

import group.zn.zero.actor.LaneKey;
import java.util.Objects;

/**
 * Actor 地址模型。
 *
 * <p>地址由 lane 类型和值表达 Actor 身份，由 owner service 字段表达远程归属。`LaneKey`
 * 仍只表示线程绑定对象，不承载 topic、实例 ID 或服务发现细节。
 *
 * @param actorType Actor 类型。
 * @param actorId Actor 标识。
 * @param ownerServiceName 归属服务名；本地地址为空字符串。
 * @param ownerServiceVersion 归属服务版本；本地地址为 0。
 * @param ownerInstanceId 归属实例标识；可为空字符串。
 * @param zone 区服或可用区；可为空字符串。
 * @author zn
 */
public record ActorAddress(
        String actorType,
        String actorId,
        String ownerServiceName,
        int ownerServiceVersion,
        String ownerInstanceId,
        String zone) {

    /**
     * 创建 Actor 地址。
     *
     * @throws NullPointerException 当 Actor 类型或标识为空时抛出。
     * @throws IllegalArgumentException 当 Actor 类型、标识或远程归属字段非法时抛出。
     */
    public ActorAddress {
        actorType = requireText(actorType, "actorType");
        actorId = requireText(actorId, "actorId");
        ownerServiceName = valueOrEmpty(ownerServiceName, "ownerServiceName");
        ownerInstanceId = valueOrEmpty(ownerInstanceId, "ownerInstanceId");
        zone = zone == null ? "" : zone;
        if (ownerServiceName.isEmpty()) {
            if (ownerServiceVersion != 0) {
                throw new IllegalArgumentException("local actor address serviceVersion must be 0");
            }
        } else if (ownerServiceVersion <= 0) {
            throw new IllegalArgumentException("remote actor address serviceVersion must be positive");
        }
    }

    /**
     * 创建本地 Actor 地址。
     *
     * @param laneKey lane key；不可为空。
     * @return 本地 Actor 地址；不可为空；线程安全。
     */
    public static ActorAddress local(final LaneKey laneKey) {
        LaneKey current = Objects.requireNonNull(laneKey, "laneKey");
        return new ActorAddress(current.type(), current.value(), "", 0, "", "local");
    }

    /**
     * 创建远程 Actor 地址。
     *
     * @param laneKey lane key；不可为空。
     * @param serviceName 归属服务名；不可为空白。
     * @param serviceVersion 归属服务版本；必须为正数。
     * @param instanceId 归属实例 ID；可为空。
     * @param zone 区服或可用区；可为空。
     * @return 远程 Actor 地址；不可为空；线程安全。
     */
    public static ActorAddress remote(
            final LaneKey laneKey,
            final String serviceName,
            final int serviceVersion,
            final String instanceId,
            final String zone) {
        LaneKey current = Objects.requireNonNull(laneKey, "laneKey");
        return new ActorAddress(current.type(), current.value(), serviceName, serviceVersion, instanceId, zone);
    }

    /**
     * 返回对应 lane key。
     *
     * @return lane key；不可为空；线程安全。
     */
    public LaneKey laneKey() {
        return new LaneKey(actorType, actorId);
    }

    /**
     * 判断地址是否指向远程归属服务。
     *
     * @return true 表示远程地址；线程安全。
     */
    public boolean remote() {
        return !ownerServiceName.isEmpty();
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
