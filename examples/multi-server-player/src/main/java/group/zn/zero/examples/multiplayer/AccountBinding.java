package group.zn.zero.examples.multiplayer;

import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.mapping.ZeroDataField;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataVersion;
import java.util.Objects;

/**
 * 不可变、线程安全的账号绑定；当前示例只在内存账号表保存。
 * @param accountId 非空账号，也是仓库主键。
 * @param uid 正数玩家 ID。
 * @param version 非负仓库版本；当前内存账号表不递增此字段。
 * @author zn
 */
@ZeroDataObject(namespace = "multiplayer", collection = "account_bindings")
public record AccountBinding(@ZeroDataId @ZeroDataField(order = 1) String accountId,
        @ZeroDataField(order = 2) long uid, @ZeroDataVersion @ZeroDataField(order = 3) long version)
        implements VersionedEntity<String> {
    /**
     * 创建未持久化绑定，无外部变更。
     * @param accountId 非空账号。 @param uid 正数玩家 ID。
     * @throws IllegalArgumentException 字段非法。 @throws NullPointerException 账号为空。
     */
    public AccountBinding(final String accountId, final long uid) { this(accountId, uid, 0L); }

    /** 校验身份与版本，无外部变更；非法值抛 IllegalArgumentException，空账号抛 NullPointerException。 */
    public AccountBinding {
        Objects.requireNonNull(accountId, "accountId");
        if (accountId.isBlank() || uid <= 0 || version < 0) {
            throw new IllegalArgumentException("invalid account binding");
        }
    }

    /** @return 不可空主键；线程安全，无状态变更。 */
    @Override
    public String id() { return accountId; }

    /**
     * @param next 非负版本。 @return 不可空新副本；线程安全，不修改当前对象。
     * @throws IllegalArgumentException next 为负数。
     */
    @Override
    public AccountBinding withVersion(final long next) { return new AccountBinding(accountId, uid, next); }
}
