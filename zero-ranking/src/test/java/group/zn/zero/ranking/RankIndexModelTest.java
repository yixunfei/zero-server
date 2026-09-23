package group.zn.zero.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 用排序参考模型覆盖跳表跨度、重排、同分、幂等与冻结。 @author zn */
class RankIndexModelTest {
    @Test void randomizedUpdatesMatchSortedReference() {
        LocalRankingService service = new LocalRankingService();
        service.transitionSeason("r", "s", SeasonState.OPEN, "open");
        Map<String, RankingEntry> reference = new HashMap<>();
        Random random = new Random(90223);
        Comparator<RankingEntry> order = Comparator.comparingLong(RankingEntry::score).reversed()
                .thenComparingLong(RankingEntry::tieBreakValue).thenComparing(RankingEntry::uid);
        for (int step = 0; step < 20_000; step++) {
            String uid = "u" + random.nextInt(400);
            long score = random.nextInt(20) - 10;
            long tie = random.nextInt(5);
            ScoreMergeMode mode = ScoreMergeMode.values()[random.nextInt(3)];
            RankingEntry old = reference.get(uid);
            long expected = old == null ? score : switch (mode) {
                case SET -> score;
                case MAX -> Math.max(old.score(), score);
                case ADD -> old.score() + score;
            };
            RankingEntry actual = service.submitScore("r", "s", uid, score, tie, mode, "k" + step);
            assertEquals(expected, actual.score());
            assertEquals(step + 1, actual.version());
            reference.put(uid, actual);
            assertEquals(actual, service.submitScore("r", "s", uid, 999, 999, mode, "k" + step));
            if (step % 41 == 0) {
                List<RankingEntry> sorted = reference.values().stream().sorted(order).toList();
                assertEquals(sorted.subList(0, Math.min(100, sorted.size())), service.queryTop("r", "s", 100));
                for (int i = 0; i < sorted.size(); i++) {
                    assertEquals(i + 1L, service.queryPlayerRank("r", "s", sorted.get(i).uid()).orElseThrow().rank());
                }
                assertTrue(service.queryPlayerRank("r", "s", "missing").isEmpty());
            }
        }
        service.transitionSeason("r", "s", SeasonState.FROZEN, "freeze");
        assertEquals(reference.values().stream().sorted(order).toList(), service.snapshot("r", "s").entries());
        assertThrows(UnsupportedOperationException.class, () -> service.queryTop("r", "s", 10).clear());
    }

    @Test void extremeScoresAndOverflowKeepIndexConsistent() {
        LocalRankingService service = new LocalRankingService();
        service.transitionSeason("r", "s", SeasonState.OPEN, "open");
        service.submitScore("r", "s", "min", Long.MIN_VALUE, 0, ScoreMergeMode.SET, "1");
        service.submitScore("r", "s", "max", Long.MAX_VALUE, 0, ScoreMergeMode.SET, "1");
        List<RankingEntry> before = service.queryTop("r", "s", 10);
        assertThrows(RuntimeException.class,
                () -> service.submitScore("r", "s", "max", 1, 1, ScoreMergeMode.ADD, "2"));
        assertEquals(before, service.queryTop("r", "s", 10));
        assertEquals(1, service.queryPlayerRank("r", "s", "max").orElseThrow().rank());
    }
}
