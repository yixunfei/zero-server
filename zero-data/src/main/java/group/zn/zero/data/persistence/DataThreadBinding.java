package group.zn.zero.data.persistence;

import java.util.Objects;

/**
 * 数据对象线程绑定键。
 *
 * <p>该类型只表达数据对象应在哪个逻辑执行域捕获快照，不直接依赖 Actor 模块。
 * 后续 starter 或 actor adapter 可以把该键映射为具体的 Actor lane。
 *
 * @param type 绑定类型。
 * @param value 绑定值。
 * @author zn
 */
public record DataThreadBinding(String type, String value) {

    /**
     * 未绑定类型。
     */
    public static final String UNBOUND_TYPE = "unbound";

    /**
     * 未绑定值。
     */
    public static final String UNBOUND_VALUE = "default";

    /**
     * 创建数据对象线程绑定键。
     *
     * @throws NullPointerException 当类型或值为空时抛出。
     * @throws IllegalArgumentException 当类型或值为空白时抛出。
     */
    public DataThreadBinding {
        type = requireText(type, "type");
        value = requireText(value, "value");
    }

    /**
     * 创建未绑定键。
     *
     * @return 未绑定键；不可为空；线程安全。
     */
    public static DataThreadBinding unbound() {
        return new DataThreadBinding(UNBOUND_TYPE, UNBOUND_VALUE);
    }

    /**
     * 创建玩家绑定键。
     *
     * @param playerId 玩家 ID；不可为空。
     * @return 玩家绑定键；不可为空；线程安全。
     */
    public static DataThreadBinding player(final String playerId) {
        return new DataThreadBinding("playerId", playerId);
    }

    /**
     * 创建场景绑定键。
     *
     * @param sceneId 场景 ID；不可为空。
     * @return 场景绑定键；不可为空；线程安全。
     */
    public static DataThreadBinding scene(final String sceneId) {
        return new DataThreadBinding("sceneId", sceneId);
    }

    /**
     * 创建实体绑定键。
     *
     * @param entityId 实体 ID；不可为空。
     * @return 实体绑定键；不可为空；线程安全。
     */
    public static DataThreadBinding entity(final String entityId) {
        return new DataThreadBinding("entityId", entityId);
    }

    /**
     * 创建自定义绑定键。
     *
     * @param type 绑定类型；不可为空。
     * @param value 绑定值；不可为空。
     * @return 自定义绑定键；不可为空；线程安全。
     */
    public static DataThreadBinding custom(final String type, final String value) {
        return new DataThreadBinding(type, value);
    }

    /**
     * 返回是否未绑定。
     *
     * @return true 表示未绑定；线程安全。
     */
    public boolean isUnbound() {
        return UNBOUND_TYPE.equals(type) && UNBOUND_VALUE.equals(value);
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
