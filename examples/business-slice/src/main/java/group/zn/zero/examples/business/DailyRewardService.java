package group.zn.zero.examples.business;

import group.zn.zero.cache.CacheService;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.event.bus.EventBus;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 每日奖励编排：权威库存读取 → 单实体条件写 → 缓存失效 → 内部事件。
 *
 * <p>库存与领取日期共用一次版本条件写，避免双仓库部分提交导致重复发奖。
 * 同玩家应串行等待整个完成链；本服务不自动切回 Actor，也不把同步后端转换成非阻塞 IO。
 * 多实例竞争依赖仓库的原子插入/版本条件写，版本冲突向调用方传播。</p>
 *
 * @author zn
 */
public final class DailyRewardService {
    /** 服务端奖励配置，客户端不能选择道具。 */
    public static final String REWARD_ITEM = "coin";
    /** 每日固定奖励数量。 */
    public static final int REWARD_QUANTITY = 100;
    /** 库存和领取标记的权威仓库。 */
    private final CrudRepository<Long, DailyRewardInventory> inventories;
    /** 仅用于查询的缓存，不决定发奖资格。 */
    private final CacheService<Long, DailyRewardInventory> cache;
    /** 至少一次通知，库存中的单槽待通知状态负责重放；消费者按事件 ID 去重。 */
    private final EventBus events;
    /** 服务端业务时钟，其时区决定每日边界。 */
    private final Clock clock;

    /**
     * 保存依赖引用，不启动资源、不修改数据。
     * @param inventories 支持原子插入和版本条件写的仓库。
     * @param cache 查询缓存。
     * @param events 内部事件通道。
     * @param clock 业务时钟；组合根选择 UTC。
     * @throws NullPointerException 任一依赖为空。
     */
    public DailyRewardService(final CrudRepository<Long, DailyRewardInventory> inventories,
            final CacheService<Long, DailyRewardInventory> cache, final EventBus events, final Clock clock) {
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 按玩家和服务端日期发放一次奖励；同日换请求键也只返回原领取结果。
     *
     * <p>uid 必须来自认证会话；DTO 的 uid 仅是本地演示输入。
     * 提交后缓存或事件失败会使完成信号失败，但重试不会再加库存；待通知状态保存在同一聚合。
     * replayNotification 可在恢复后重放；未确认旧通知时拒绝下一天领取，避免积压无限增长。</p>
     *
     * @param uid 已认证玩家 ID，必须为正数。
     * @param idempotencyKey 非空请求标识，用于回显第一次领取请求。
     * @param itemId 客户端预期道具，必须与服务端配置一致。
     * @param quantity 客户端预期数量，必须与服务端配置一致。
     * @param traceId 非空追踪标识。
     * @return 不可空异步结果；同玩家须串行，失败信号保留依赖异常。
     * @throws ZeroException 输入非法或依赖同步失败；库存溢出以失败信号传播。
     */
    public CompletionStage<DailyRewardClaimResult> claim(final long uid, final String idempotencyKey,
            final String itemId, final int quantity, final String traceId) {
        validateRequest(uid, idempotencyKey, itemId, quantity, traceId);
        String day = LocalDate.now(clock).toString();
        // 缓存失效失败或陈旧副本不能重新赋予领取资格。
        return inventories.findById(uid).thenCompose(found -> {
            DailyRewardInventory current = found.orElseGet(() -> DailyRewardInventory.empty(uid));
            if (day.compareTo(current.claimDay()) < 0) {
                throw invalid("server date precedes the last committed claim date");
            }
            if (day.equals(current.claimDay())) {
                return CompletableFuture.completedFuture(result(
                        DailyRewardClaimResult.Status.DUPLICATE, current.claimKey()));
            }
            if (current.pendingNotification()) throw invalid("previous notification must be replayed before the next claim");
            DailyRewardInventory next = current.claim(day, idempotencyKey, traceId);
            return inventories.save(next)
                    .thenCompose(ignored -> cache.invalidate(uid))
                    .thenCompose(ignored -> replayNotification(uid))
                    .thenApply(ignored -> result(DailyRewardClaimResult.Status.ACCEPTED, idempotencyKey));
        });
    }

    /** 重放单个玩家的已提交通知；成功后 CAS 确认，发送成功但确认失败允许重复发送。
     * @param uid 玩家 ID。 @return 是否曾有待通知事件；同玩家应与 claim 串行。
     * @throws RuntimeException 查询/通知/确认失败；不会再次增加库存。 */
    public CompletionStage<Boolean> replayNotification(final long uid) {
        return inventories.findById(uid).thenCompose(found -> {
            if (found.isEmpty() || !found.orElseThrow().pendingNotification()) {
                return CompletableFuture.completedFuture(false);
            }
            DailyRewardInventory current = found.orElseThrow();
            return events.publish(new DailyRewardClaimedEvent("daily-reward-" + uid + "-" + current.claimDay(),
                    current.notificationTrace(), uid, REWARD_ITEM, REWARD_QUANTITY))
                    .thenCompose(ignored -> inventories.save(current.notified()))
                    .thenCompose(ignored -> cache.invalidate(uid)).thenApply(ignored -> true);
        });
    }

    /**
     * 查询库存，缓存未命中时回源并回填不可变实体。
     * @param uid 正数玩家 ID。
     * @return 不可空异步库存；不存在时为零库存；查询可能修改缓存。
     * @throws ZeroException uid 非法或依赖同步失败；同玩家须串行等待完成。
     */
    public CompletionStage<DailyRewardInventory> loadInventory(final long uid) {
        if (uid <= 0) {
            throw invalid("uid must be positive");
        }
        return cache.get(uid).thenCompose(cached -> {
            if (cached.isPresent()) {
                return CompletableFuture.completedFuture(cached.orElseThrow());
            }
            return inventories.findById(uid).thenCompose(found -> {
                DailyRewardInventory inventory = found.orElseGet(() -> DailyRewardInventory.empty(uid));
                return cache.put(uid, inventory).thenApply(ignored -> inventory);
            });
        });
    }

    private static DailyRewardClaimResult result(final DailyRewardClaimResult.Status status, final String key) {
        return new DailyRewardClaimResult(status, key, REWARD_ITEM, REWARD_QUANTITY);
    }

    private static void validateRequest(final long uid, final String key, final String item,
            final int quantity, final String traceId) {
        if (uid <= 0 || key == null || key.isBlank() || !REWARD_ITEM.equals(item)
                || quantity != REWARD_QUANTITY || traceId == null || traceId.isBlank()) {
            throw invalid("positive uid, request key, traceId and server reward coin/100 are required");
        }
    }

    private static ZeroException invalid(final String message) {
        return ZeroException.of(SystemErrorCode.INVALID_ARGUMENT, message, null);
    }
}
