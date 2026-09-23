package group.zn.zero.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 同步通知、提交后失败、跨榜重入及精确快照缓存的契约回归。 @author zn */
class RankingNotificationContractTest {
    /** sink 失败仍提交，重试幂等不再次通知；metrics 仅在 sink 成功后执行。 */
    @Test void sinkFailureKeepsCommittedStateAndInvalidatesTop() {
        var failure = new IllegalStateException("sink");
        List<String> metrics = new ArrayList<>();
        var service = new LocalRankingService(event -> {
            if (event.type().equals("SCORE_SUBMITTED")) throw failure;
        }, (operation, result) -> metrics.add(operation));
        service.transitionSeason("r", "s", SeasonState.OPEN, "open");
        var old = service.queryTop("r", "s", 10);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.submitScore("r", "s", "u", 10, 0, ScoreMergeMode.SET, "one")));
        assertEquals(1, service.queryTop("r", "s", 10).size());
        assertEquals(0, old.size());
        assertEquals(1, service.submitScore("r", "s", "u", 20, 0, ScoreMergeMode.SET, "one").version());
        assertEquals(List.of(), metrics);
    }
    /** 同步回调可查询已提交状态并跨榜写入；通知和完成顺序不变。 */
    @Test void callbacksCanReenterAcrossBoards() {
        List<String> calls = new ArrayList<>();
        LocalRankingService[] holder = new LocalRankingService[1];
        holder[0] = new LocalRankingService(event -> {
            if (!event.type().equals("SCORE_SUBMITTED")) return;
            calls.add(event.rankingId());
            assertEquals(1, holder[0].queryTop(event.rankingId(), "s", 10).size());
            if (event.rankingId().equals("a")) {
                holder[0].submitScore("b", "s", "v", 20, 0, ScoreMergeMode.SET, "nested");
                calls.add("a-return");
            }
        }, (operation, result) -> calls.add("metric"));
        var service = holder[0];
        service.transitionSeason("a", "s", SeasonState.OPEN, "open");
        service.transitionSeason("b", "s", SeasonState.OPEN, "open");
        service.submitScore("a", "s", "u", 10, 0, ScoreMergeMode.SET, "one");
        assertEquals(List.of("a", "b", "metric", "a-return", "metric"), calls);
        var top = service.queryTop("a", "s", 10);
        assertSame(top, service.queryTop("a", "s", 10));
        assertThrows(UnsupportedOperationException.class, top::clear);
    }
}
