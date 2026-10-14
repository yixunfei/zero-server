package group.zn.zero.ranking;

import group.zn.zero.actor.LaneKey;
import java.util.List;
import java.util.Optional;

/**
 * 线程安全的本地排行榜 API；查询返回不可变快照。
 * 赛季序号作用域为服务实例，评分序号作用域为单个榜单赛季，二者不可互相比较。
 * 调用方维护正单调序号和归档进度，重试必须复用原序号与幂等键。
 * @author zn
 */
public interface RankingService {
    /** 合并已开放赛季的评分，线程安全；提交后通知失败不回滚评分。
     * @param rankingId 榜单。 @param seasonId 赛季。 @param uid 玩家。
     * @param score 分数。 @param tieBreakValue 同分排序值。 @param mode 合并规则。
     * @param sequence 正评分序号，作用域为榜单赛季；重试复用且不得落入归档水位。
     * @param idempotencyKey 玩家内的稳定请求键。 @return 不可变已提交条目。
     * @throws group.zn.zero.core.error.ZeroException 状态非法、收据冲突/满、评分溢出或观察失败。
     * @throws IllegalArgumentException 序号非正或请求键为空白。 */
    RankingEntry submitScore(String rankingId, String seasonId, String uid, long score,
                             long tieBreakValue, ScoreMergeMode mode, long sequence, String idempotencyKey);
    /** @param rankingId 榜单。 @param seasonId 赛季。 @param limit 1..100。
     * @return 不可变有序 Top，可为空；只读、线程安全。
     * @throws group.zn.zero.core.error.ZeroException 查询上限非法。 */
    List<RankingEntry> queryTop(String rankingId, String seasonId, int limit);
    /** @param rankingId 榜单。 @param seasonId 赛季。 @param uid 玩家。
     * @return 不可变排名或 empty；只读、线程安全。 */
    Optional<PlayerRank> queryPlayerRank(String rankingId, String seasonId, String uid);
    /** 导出冻结榜单并标记可归档；线程安全。
     * @param rankingId 榜单。 @param seasonId 赛季。
     * @return 不可变有序全量快照，可为空。
     * @throws group.zn.zero.core.error.ZeroException 赛季未冻结。 */
    RankSnapshot snapshot(String rankingId, String seasonId);
    /**
     * 查询指定代际；退役水位内缺失为 ARCHIVED，未来缺失为 CREATED；不保留已退役的原终态。
     * @param rankingId 榜单。 @param seasonId 赛季。 @param seasonSequence 正赛季序号。
     * @return 状态；只读、线程安全。
     * @throws group.zn.zero.core.error.ZeroException 活跃标识的序号不匹配。
     * @throws IllegalArgumentException 序号非正。
     */
    SeasonState seasonState(String rankingId, String seasonId, long seasonSequence);
    /** 校验后提交状态；线程安全，通知失败不撤销转换。
     * @param rankingId 榜单。 @param seasonId 赛季。 @param target 目标状态。
     * @param sequence 正赛季序号，作用域为服务实例，同一赛季所有转换复用。
     * @param idempotencyKey 转换请求键。 @return 提交后的状态。
     * @throws group.zn.zero.core.error.ZeroException 旧赛季、标识冲突或非法转换。
     * @throws IllegalArgumentException 序号非正。 */
    SeasonState transitionSeason(String rankingId, String seasonId, SeasonState target,
                                 long sequence, String idempotencyKey);
    /** 登记幂等结算并推进 SETTLED；线程安全，回调失败不回滚。
     * @param rankingId 榜单。 @param seasonId 赛季。 @param settlementId 结算键。
     * @return 首次 true，重复 false。
     * @throws group.zn.zero.core.error.ZeroException 赛季不处于 SETTLING。 */
    boolean settle(String rankingId, String seasonId, String settlementId);
    /**
     * 调用方确认边界前的请求全部完成且结果已归档后释放评分收据。
     * @param rankingId 榜单。 @param seasonId 赛季。 @param sequence 包含的正请求边界。
     * @return 释放数量；线程安全。旧序号永久拒绝，不会再次 ADD。
     * @throws group.zn.zero.core.error.ZeroException 无赛季或水位回退。
     */
    int acknowledgeRequestsThrough(String rankingId, String seasonId, long sequence);
    /**
     * 释放已导出快照且 ARCHIVED 的赛季或 CANCELLED 赛季；全部符合才原子删除。
     * 使用服务级赛季序号水位，之后旧序号不可重建；应用须保证边界前不再有新赛季到达。
     * @param sequence 包含归档边界。 @return 删除榜单数；线程安全。
     * @throws group.zn.zero.core.error.ZeroException 存在未完成归档的赛季。
     * @throws IllegalArgumentException 水位非正或回退。
     */
    int retireSeasonsThrough(long sequence);
    /** @param rankingId 榜单。 @param seasonId 赛季。
     * @return 本地投递 lane；只读、线程安全，同分隔符的标识可能共享 lane，应用应统一命名。 */
    default LaneKey laneKey(String rankingId, String seasonId) { return LaneKey.custom(rankingId + ":" + seasonId); }
}
