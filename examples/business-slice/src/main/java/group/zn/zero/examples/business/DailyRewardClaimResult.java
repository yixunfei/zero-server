package group.zn.zero.examples.business;

/**
 * 每日道具领取结果。
 *
 * @param status 本次请求状态。
 * @param idempotencyKey 幂等键。
 * @param itemId 道具 ID。
 * @param quantity 本次请求对应的道具数量。
 * @author zn
 */
public record DailyRewardClaimResult(
        Status status,
        String idempotencyKey,
        String itemId,
        int quantity) {

    /** 领取状态。 */
    public enum Status {
        /** 首次领取并完成发放。 */
        ACCEPTED,
        /** 该玩家当天已领取，回显原领取键，不重复发放。 */
        DUPLICATE
    }

    /**
     * 创建并校验领取结果。
     *
     * @throws NullPointerException 状态、幂等键或道具为空时抛出。
     */
    public DailyRewardClaimResult {
        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        java.util.Objects.requireNonNull(itemId, "itemId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
