package group.zn.zero.player;

import group.zn.zero.data.model.VersionedEntity;
import java.util.Objects;

/**
 * 玩家在线档案。
 *
 * <p>该档案表示阶段 3 原型中的最小玩家在线状态。record 不可变且线程安全；
 * 状态替换应在 player lane 内完成。
 *
 * @param uid 玩家 ID。
 * @param version 版本号。
 * @param name 玩家名。
 * @param online 是否在线。
 * @author zn
 */
public record PlayerProfile(long uid, long version, String name, boolean online) implements VersionedEntity<Long> {

    /**
     * 创建版本号为 0 的玩家在线档案。
     *
     * @param uid 玩家 ID。
     * @param name 玩家名。
     * @param online 是否在线。
     */
    public PlayerProfile(final long uid, final String name, final boolean online) {
        this(uid, 0L, name, online);
    }

    /**
     * 创建玩家在线档案。
     *
     * @throws NullPointerException 当玩家名为空时抛出。
     * @throws IllegalArgumentException 当玩家名为空白时抛出。
     */
    public PlayerProfile {
        name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * 返回实体主键。
     *
     * @return 玩家 ID；不可为空；线程安全。
     */
    @Override
    public Long id() {
        return uid;
    }

    /**
     * 返回设置新版本号后的玩家档案。
     *
     * @param version 新版本号。
     * @return 玩家档案；不可为空；线程安全。
     */
    @Override
    public PlayerProfile withVersion(final long version) {
        return new PlayerProfile(uid, version, name, online);
    }
}
