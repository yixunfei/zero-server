package group.zn.zero.ranking;

import group.zn.zero.actor.LaneKey;
import java.util.List;
import java.util.Optional;

/** Public local ranking API. Implementations own one actor lane per ranking-season key. */
public interface RankingService {
    RankingEntry submitScore(String rankingId, String seasonId, String uid, long score,
                             long tieBreakValue, ScoreMergeMode mode, String idempotencyKey);
    List<RankingEntry> queryTop(String rankingId, String seasonId, int limit);
    Optional<PlayerRank> queryPlayerRank(String rankingId, String seasonId, String uid);
    RankSnapshot snapshot(String rankingId, String seasonId);
    SeasonState seasonState(String rankingId, String seasonId);
    SeasonState transitionSeason(String rankingId, String seasonId, SeasonState target, String idempotencyKey);
    boolean settle(String rankingId, String seasonId, String settlementId);
    default LaneKey laneKey(String rankingId, String seasonId) { return LaneKey.custom(rankingId + ":" + seasonId); }
}
