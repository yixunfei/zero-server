package group.zn.zero.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.ZeroException;
import org.junit.jupiter.api.Test;

/** 评分收据与赛季退役的重放保护。 @author zn */
class RankingRetentionTest {
    /** 清理 ADD 收据后旧请求拒绝，较新请求继续正常累加。 */
    @Test void requestWatermarkPreventsDuplicateAdds() {
        var service = new LocalRankingService();
        service.transitionSeason("r", "001", SeasonState.OPEN, "open");
        service.submitScore("r", "001", "u", 10, 0, ScoreMergeMode.ADD, "001");
        assertEquals(1, service.acknowledgeRequestsThrough("r", "001", "001"));
        assertThrows(ZeroException.class,
                () -> service.submitScore("r", "001", "u", 10, 0, ScoreMergeMode.ADD, "001"));
        service.submitScore("r", "001", "u", 5, 0, ScoreMergeMode.ADD, "002");
        assertEquals(15, service.queryTop("r", "001", 1).getFirst().score());
    }

    /** 整批校验后才回收；旧赛季不能通过状态转换或评分重新创建。 */
    @Test void retirementRequiresExportAndRejectsOldSeasons() {
        var service = new LocalRankingService();
        service.transitionSeason("r", "001", SeasonState.OPEN, "open");
        assertThrows(ZeroException.class, () -> service.retireSeasonsThrough("001"));
        service.transitionSeason("r", "001", SeasonState.FROZEN, "freeze");
        service.snapshot("r", "001");
        service.transitionSeason("r", "001", SeasonState.SETTLING, "settling");
        service.settle("r", "001", "settle");
        service.transitionSeason("r", "001", SeasonState.ARCHIVED, "archive");
        assertEquals(1, service.retireSeasonsThrough("001"));
        assertEquals(SeasonState.ARCHIVED, service.seasonState("r", "001"));
        assertTrue(service.queryTop("r", "001", 1).isEmpty());
        assertThrows(ZeroException.class, () -> service.transitionSeason("r", "001", SeasonState.OPEN, "again"));
        assertThrows(ZeroException.class,
                () -> service.submitScore("r", "001", "u", 1, 0, ScoreMergeMode.ADD, "new"));
        service.transitionSeason("r", "002", SeasonState.OPEN, "open");
    }

    /** 评分收据使用显式序号，不依赖 UUID/字符串字典序。 */
    @Test void explicitSequenceWatermarkRejectsOlderRequest() {
        var service = new LocalRankingService();
        service.transitionSeason("r", "uuid-season", SeasonState.OPEN, 100L, "open");
        service.submitScore("r", "uuid-season", "u", 10, 0, ScoreMergeMode.ADD, 101L, "uuid-z");
        assertEquals(1, service.acknowledgeRequestsThrough("r", "uuid-season", 101L));
        assertThrows(ZeroException.class,
                () -> service.submitScore("r", "uuid-season", "u", 10, 0, ScoreMergeMode.ADD, 99L, "uuid-a"));
    }
}
