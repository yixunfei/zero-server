package group.zn.zero.examples.business;

import group.zn.zero.event.EventType;
import group.zn.zero.event.ZeroEvent;
import java.util.Objects;

/**
 * 每日道具领取成功后的内部业务事件。
 *
 * @param eventId 事件唯一标识。
 * @param traceId 链路追踪标识。
 * @param uid 玩家 ID。
 * @param itemId 道具 ID。
 * @param quantity 发放数量。
 * @author zn
 */
public record DailyRewardClaimedEvent(
        String eventId,
        String traceId,
        long uid,
        String itemId,
        int quantity) implements ZeroEvent {

    /**
     * 创建内部业务事件。
     */
    public DailyRewardClaimedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(itemId, "itemId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    /**
     * 返回事件类型。
     *
     * @return 内部事件类型；线程安全。
     */
    @Override
    public EventType eventType() {
        return EventType.INTERNAL;
    }
}
