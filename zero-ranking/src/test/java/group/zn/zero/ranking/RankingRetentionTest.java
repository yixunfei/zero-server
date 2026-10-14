package group.zn.zero.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.ZeroException;
import org.junit.jupiter.api.Test;

/** 评分收据与赛季退役的重放保护。 @author zn */
class RankingRetentionTest {
    /** 拒绝评分和非法初始转换不发布空榜单，不能阻塞无关赛季退役。 */
    @Test void rejectedAdmissionDoesNotRetainDirectories() throws Exception {
        var service = new LocalRankingService();
        for (int i = 1; i <= 1000; i++) {
            String id = "rejected-" + i;
            assertThrows(ZeroException.class,
                    () -> service.submitScore("r", id, "u", 1, 0, ScoreMergeMode.ADD, 1, id));
            assertThrows(ZeroException.class,
                    () -> service.transitionSeason("r", id, SeasonState.FROZEN, 1, id));
        }
        assertEquals(0, retainedMapEntries(service));
        service.transitionSeason("r", "valid", SeasonState.CANCELLED, 10, "cancel");
        assertEquals(1, service.retireSeasonsThrough(10));
        assertEquals(SeasonState.ARCHIVED, service.seasonState("r", "valid", 10));
        assertEquals(SeasonState.CREATED, service.seasonState("r", "future", 11));
        service.transitionSeason("r", "future", SeasonState.OPEN, 11, "open");
        assertEquals(SeasonState.OPEN, service.seasonState("r", "future", 11));
        assertThrows(ZeroException.class, () -> service.seasonState("r", "future", 12));
        // 评分水位与赛季水位无关：赛季 11 的评分序号 1 合法。
        service.submitScore("r", "future", "u", 1, 0, ScoreMergeMode.ADD, 1, "score");
    }

    /** 大量评分归档及赛季退役后，所有历史映射均释放，水位仍阻止重放。 */
    @Test void churnRetainsOnlyActiveStateAndScalarWatermarks() throws Exception {
        var service = new LocalRankingService();
        for (int i = 1; i <= 1000; i++) {
            String season = "season-" + i;
            service.transitionSeason("r", season, SeasonState.OPEN, i, "open");
            service.submitScore("r", season, "u", 1, 0, ScoreMergeMode.ADD, 1, "score");
            assertEquals(1, service.acknowledgeRequestsThrough("r", season, 1));
            assertThrows(ZeroException.class,
                    () -> service.submitScore("r", season, "u", 1, 0, ScoreMergeMode.ADD, 1, "score"));
            service.transitionSeason("r", season, SeasonState.CANCELLED, i, "cancel");
            assertEquals(1, service.retireSeasonsThrough(i));
            assertEquals(0, retainedMapEntries(service));
        }
        assertEquals(0, service.retireSeasonsThrough(1000));
        assertThrows(IllegalArgumentException.class, () -> service.retireSeasonsThrough(999));
        assertThrows(ZeroException.class,
                () -> service.transitionSeason("r", "old", SeasonState.OPEN, 999, "open"));
    }

    private static int retainedMapEntries(final Object owner) throws Exception {
        int entries = 0;
        for (var field : owner.getClass().getDeclaredFields()) {
            if (java.util.Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                entries += ((java.util.Map<?, ?>) field.get(owner)).size();
            }
        }
        return entries;
    }
    /** 清理 ADD 收据后旧请求拒绝，较新请求继续正常累加。 */
    @Test void requestWatermarkPreventsDuplicateAdds() {
        var service = new LocalRankingService();
        service.transitionSeason("r", "001", SeasonState.OPEN, 1L, "open");
        service.submitScore("r", "001", "u", 10, 0, ScoreMergeMode.ADD, 1L, "001");
        assertEquals(1, service.acknowledgeRequestsThrough("r", "001", 1L));
        assertThrows(ZeroException.class,
                () -> service.submitScore("r", "001", "u", 10, 0, ScoreMergeMode.ADD, 1L, "001"));
        service.submitScore("r", "001", "u", 5, 0, ScoreMergeMode.ADD, 2L, "002");
        assertEquals(15, service.queryTop("r", "001", 1).getFirst().score());
    }

    /** 整批校验后才回收；旧赛季不能通过状态转换或评分重新创建。 */
    @Test void retirementRequiresExportAndRejectsOldSeasons() {
        var service = new LocalRankingService();
        service.transitionSeason("r", "001", SeasonState.OPEN, 1L, "open");
        assertThrows(ZeroException.class, () -> service.retireSeasonsThrough( 1L));
        service.transitionSeason("r", "001", SeasonState.FROZEN, 1L, "freeze");
        service.snapshot("r", "001");
        service.transitionSeason("r", "001", SeasonState.SETTLING, 1L, "settling");
        service.settle("r", "001", "settle");
        service.transitionSeason("r", "001", SeasonState.ARCHIVED, 1L, "archive");
        assertEquals(1, service.retireSeasonsThrough( 1L));
        assertEquals(SeasonState.ARCHIVED, service.seasonState("r", "001", 1L));
        assertTrue(service.queryTop("r", "001", 1).isEmpty());
        assertThrows(ZeroException.class, () -> service.transitionSeason("r", "001", SeasonState.OPEN, 1L, "again"));
        assertThrows(ZeroException.class,
                () -> service.submitScore("r", "001", "u", 1, 0, ScoreMergeMode.ADD, 1L, "new"));
        service.transitionSeason("r", "002", SeasonState.OPEN, 2L, "open");
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
