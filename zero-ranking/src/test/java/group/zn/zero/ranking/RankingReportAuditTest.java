package group.zn.zero.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.core.error.ZeroException;
import org.junit.jupiter.api.Test;

/** 排行榜溢出与 Top-N 契约回归。 @author zn */
class RankingReportAuditTest {
    /** 溢出使用业务错误码，且不得改变原分数。 */
    @Test void overflowIsBusinessFailureWithoutMutation() {
        LocalRankingService service = new LocalRankingService();
        service.transitionSeason("r", "s", SeasonState.OPEN, "open");
        service.submitScore("r", "s", "u", Long.MAX_VALUE, 0, ScoreMergeMode.SET, "a");
        ZeroException ex = assertThrows(ZeroException.class,
                () -> service.submitScore("r", "s", "u", 1, 0, ScoreMergeMode.ADD, "b"));
        assertEquals(RankingErrorCode.RANKING_SCORE_REJECTED, ex.errorCode());
        assertEquals(Long.MAX_VALUE, service.queryTop("r", "s", 1).getFirst().score());
    }
    /** Top-N 与完整冻结快照各自履行契约，不是分页接口。 */
    @Test void topAndSnapshotHaveDifferentScopes() {
        LocalRankingService service = new LocalRankingService();
        service.transitionSeason("r", "s", SeasonState.OPEN, "open");
        for (int i = 0; i < 150; i++) service.submitScore("r", "s", "p" + i, 150 - i, 0, ScoreMergeMode.SET, "a");
        service.transitionSeason("r", "s", SeasonState.FROZEN, "freeze");
        assertEquals(100, service.queryTop("r", "s", 100).size());
        assertEquals(150, service.snapshot("r", "s").entries().size());
        assertEquals(150, service.queryPlayerRank("r", "s", "p149").orElseThrow().rank());
    }
    /** 契约仅允许冻结赛季生成完整快照。 */
    @Test void snapshotRequiresFrozenSeason() {
        LocalRankingService service = new LocalRankingService();
        service.transitionSeason("r", "s", SeasonState.OPEN, "open");
        ZeroException failure = assertThrows(ZeroException.class, () -> service.snapshot("r", "s"));
        assertEquals(RankingErrorCode.RANKING_SEASON_STATE_INVALID, failure.errorCode());
    }

}
