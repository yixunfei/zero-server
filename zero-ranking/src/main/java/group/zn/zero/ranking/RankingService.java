package group.zn.zero.ranking;

import group.zn.zero.actor.LaneKey;
import java.util.List;
import java.util.Optional;

/** Public local ranking API. Implementations own one actor lane per ranking-season key. */
public interface RankingService {
    RankingEntry submitScore(String rankingId, String seasonId, String uid, long score,
                             long tieBreakValue, ScoreMergeMode mode, String idempotencyKey);
    RankingEntry submitScore(String rankingId, String seasonId, String uid, long score,
                             long tieBreakValue, ScoreMergeMode mode, long sequence, String idempotencyKey);
    List<RankingEntry> queryTop(String rankingId, String seasonId, int limit);
    Optional<PlayerRank> queryPlayerRank(String rankingId, String seasonId, String uid);
    RankSnapshot snapshot(String rankingId, String seasonId);
    SeasonState seasonState(String rankingId, String seasonId);
    SeasonState transitionSeason(String rankingId, String seasonId, SeasonState target, String idempotencyKey);
    SeasonState transitionSeason(String rankingId, String seasonId, SeasonState target,
                                 long sequence, String idempotencyKey);
    boolean settle(String rankingId, String seasonId, String settlementId);
    /**
     * 调用方确认结果已归档后释放评分收据；新幂等键必须按 String.compareTo 大于该水位。
     * @return 释放数量；线程安全。旧键永久拒绝，不会再次 ADD。
     */
    int acknowledgeRequestsThrough(String rankingId, String seasonId, String idempotencyKey);
    /** 使用显式单调序号确认评分收据归档边界。 */
    int acknowledgeRequestsThrough(String rankingId, String seasonId, long sequence);
    /**
     * 释放已导出快照且 ARCHIVED 的赛季或 CANCELLED 赛季；全部符合才原子删除。
     * 赛季 ID 使用 String.compareTo 的全局单调水位，之后旧赛季不可重建。
     * @param seasonId 包含归档边界。 @return 删除榜单数；线程安全。
     */
    int retireSeasonsThrough(String seasonId);
    /** 使用显式单调序号释放已导出的终态赛季。 */
    int retireSeasonsThrough(long sequence);
    default LaneKey laneKey(String rankingId, String seasonId) { return LaneKey.custom(rankingId + ":" + seasonId); }
}
