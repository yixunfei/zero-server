package group.zn.zero.examples.rankingseason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class RankingSeasonApplicationTest {
    @Test void demoCompletesAndUsesFormalApi() {
        var result = RankingSeasonApplication.runDemo();
        assertEquals("ARCHIVED", result.status().name());
        assertEquals(2, result.top().size());
        assertEquals(180, result.top().get(1).score());
        assertEquals(3, result.snapshot().entries().size());
        assertEquals(true, result.dryRun());
        assertEquals(true, result.execute());
        assertEquals(false, result.replay());
        assertEquals(true, result.marker().startsWith("ranking-season=ok|mode=local|"));
        assertEquals(true, result.marker().endsWith("|productionReady=false"));
    }

    @Test void formalApiRejectsWritesAfterFreeze() {
        var ranking = new group.zn.zero.ranking.LocalRankingService();
        ranking.transitionSeason("r", "s", group.zn.zero.ranking.SeasonState.OPEN, "open");
        ranking.submitScore("r", "s", "1", 10, 1, group.zn.zero.ranking.ScoreMergeMode.SET, "a");
        ranking.transitionSeason("r", "s", group.zn.zero.ranking.SeasonState.FROZEN, "freeze");
        assertThrows(RuntimeException.class, () -> ranking.submitScore("r", "s", "1", 1, 1,
                group.zn.zero.ranking.ScoreMergeMode.SET, "b"));
    }
}
