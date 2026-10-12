package group.zn.zero.examples.multiplayer;

import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.mapping.ZeroDataField;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataVersion;
import java.util.Objects;

/**
 * 不可变、线程安全的角色快照；一个账号只创建一个角色。
 * @param uid 正数玩家 ID，也是仓库主键。
 * @param version 非负仓库版本；创建为零，保存后必须使用读回的提交版本。
 * @param characterName 非空角色名。
 * @param level 正数等级。
 * @author zn
 */
@ZeroDataObject(namespace = "multiplayer", collection = "player_characters")
public record PlayerCharacter(@ZeroDataId @ZeroDataField(order = 1) long uid,
        @ZeroDataVersion @ZeroDataField(order = 2) long version,
        @ZeroDataField(order = 3) String characterName, @ZeroDataField(order = 4) int level)
        implements VersionedEntity<Long> {
    /**
     * 创建一级角色，不写仓库。
     * @param uid 正数 ID。 @param characterName 非空名称。
     * @throws IllegalArgumentException 字段非法。 @throws NullPointerException 名称为空。
     */
    public PlayerCharacter(final long uid, final String characterName) { this(uid, 0L, characterName, 1); }

    /** 校验实体；非法值抛 IllegalArgumentException，空名称抛 NullPointerException，无外部变更。 */
    public PlayerCharacter {
        Objects.requireNonNull(characterName, "characterName");
        if (uid <= 0 || version < 0 || level <= 0 || characterName.isBlank()) {
            throw new IllegalArgumentException("invalid character");
        }
    }

    /** @return 不可空主键；线程安全，无副作用。 */
    @Override
    public Long id() { return uid; }

    /**
     * @param next 非负版本。 @return 不可空新快照；线程安全，不修改当前对象。
     * @throws IllegalArgumentException next 为负数。
     */
    @Override
    public PlayerCharacter withVersion(final long next) {
        return new PlayerCharacter(uid, next, characterName, level);
    }
}
