package group.zn.zero.actor;

/**
 * Actor lane 绑定键。
 *
 * @param type 绑定类型。
 * @param value 绑定值。
 * @author zn
 */
public record LaneKey(String type, String value) {

    /**
     * 创建 lane 绑定键。
     *
     * @throws NullPointerException 当类型或值为空时抛出。
     */
    public LaneKey {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(value, "value");
    }

    /**
     * 创建玩家 lane key。
     *
     * @param playerId 玩家 ID；不可为空。
     * @return 玩家 lane key；不可为空；线程安全。
     */
    public static LaneKey player(final String playerId) {
        return new LaneKey("playerId", playerId);
    }

    /**
     * 创建服务器 lane key。
     *
     * @param serverId 服务器 ID；不可为空。
     * @return 服务器 lane key；不可为空；线程安全。
     */
    public static LaneKey server(final String serverId) {
        return new LaneKey("serverId", serverId);
    }

    /**
     * 创建场景 lane key。
     *
     * @param sceneId 场景 ID；不可为空。
     * @return 场景 lane key；不可为空；线程安全。
     */
    public static LaneKey scene(final String sceneId) {
        return new LaneKey("sceneId", sceneId);
    }

    /**
     * 创建实体 lane key。
     *
     * @param entityId 实体 ID；不可为空。
     * @return 实体 lane key；不可为空；线程安全。
     */
    public static LaneKey entity(final String entityId) {
        return new LaneKey("entityId", entityId);
    }

    /**
     * 创建会话 lane key。
     *
     * @param sessionId 会话 ID；不可为空。
     * @return 会话 lane key；不可为空；线程安全。
     */
    public static LaneKey session(final String sessionId) {
        return new LaneKey("sessionId", sessionId);
    }

    /**
     * 创建事件 lane key。
     *
     * @param eventId 事件 ID；不可为空。
     * @return 事件 lane key；不可为空；线程安全。
     */
    public static LaneKey event(final String eventId) {
        return new LaneKey("eventId", eventId);
    }

    /**
     * 创建自定义 lane key。
     *
     * @param customKey 自定义键；不可为空。
     * @return 自定义 lane key；不可为空；线程安全。
     */
    public static LaneKey custom(final String customKey) {
        return new LaneKey("customKey", customKey);
    }
}
