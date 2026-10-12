package group.zn.zero.examples.business;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.mapping.ZeroDataField;
import group.zn.zero.data.mapping.ZeroDataId;
import group.zn.zero.data.mapping.ZeroDataObject;
import group.zn.zero.data.mapping.ZeroDataVersion;
import group.zn.zero.data.model.VersionedEntity;
import java.time.LocalDate;

/**
 * 不可变、线程安全的玩家奖励聚合，库存与领取标记共同参与版本条件写。
 * @param id 正数玩家 ID。
 * @param version 非负仓库版本；零表示待插入。
 * @param itemId 固定奖励道具。
 * @param quantity 非负库存。
 * @param claimDay 最近领取的 ISO 日期；从未领取时为空字符串。
 * @param claimKey 最近领取请求标识；从未领取时为空字符串。
 * @author zn
 */
@ZeroDataObject(namespace = "business_slice", collection = "daily_reward_inventory", schemaVersion = 2)
public record DailyRewardInventory(
        @ZeroDataId Long id,
        @ZeroDataVersion long version,
        @ZeroDataField(order = 1) String itemId,
        @ZeroDataField(order = 2) int quantity,
        @ZeroDataField(order = 3) String claimDay,
        @ZeroDataField(order = 4) String claimKey) implements VersionedEntity<Long> {

    /**
     * 校验快照，不修改外部数据。
     * @throws IllegalArgumentException ID、版本、库存、道具或成对领取标记非法。
     * @throws java.time.format.DateTimeParseException 日期格式非法。
     */
    public DailyRewardInventory {
        if (id == null || id <= 0 || version < 0 || !DailyRewardService.REWARD_ITEM.equals(itemId)
                || quantity < 0 || claimDay == null || claimKey == null
                || claimDay.isEmpty() != claimKey.isEmpty()
                || (!claimKey.isEmpty() && claimKey.isBlank())) {
            throw new IllegalArgumentException("invalid daily reward inventory");
        }
        if (!claimDay.isEmpty()) {
            LocalDate.parse(claimDay);
        }
    }

    /**
     * 创建未领取的零库存；线程安全，不写仓库。
     * @param uid 正数玩家 ID。
     * @return 不可空、不可变实体。
     * @throws IllegalArgumentException uid 非法。
     */
    public static DailyRewardInventory empty(final long uid) {
        return new DailyRewardInventory(uid, 0, DailyRewardService.REWARD_ITEM, 0, "", "");
    }

    /**
     * 创建指定版本副本；线程安全，不修改当前实体。
     * @param next 非负新版本。
     * @return 不可空、不可变副本。
     * @throws IllegalArgumentException 版本非法。
     */
    @Override
    public DailyRewardInventory withVersion(final long next) {
        return new DailyRewardInventory(id, next, itemId, quantity, claimDay, claimKey);
    }

    /** 由服务校验领取资格后构造一次写入的完整快照。 */
    DailyRewardInventory claim(final String day, final String key) {
        try {
            return new DailyRewardInventory(id, version, itemId,
                    Math.addExact(quantity, DailyRewardService.REWARD_QUANTITY), day, key);
        } catch (ArithmeticException failure) {
            throw ZeroException.of(SystemErrorCode.INVALID_ARGUMENT, "inventory quantity overflow", failure);
        }
    }
}
