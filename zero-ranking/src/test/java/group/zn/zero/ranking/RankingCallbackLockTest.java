package group.zn.zero.ranking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 慢事件消费者不得占用排行榜状态锁。 @author zn */
class RankingCallbackLockTest {
    /** 提交、状态转换和结算的通知均在锁外；慢通知期间状态可查询和独立更新。 */
    @Test void callbacksDoNotHoldStateMonitor() throws Exception {
        for (String eventType : new String[]{"SCORE_SUBMITTED", "SEASON_OPEN", "SETTLED"}) {
            var entered = new CountDownLatch(1);
            var release = new CompletableFuture<Void>();
            var blockedType = new AtomicReference<String>();
            var service = new LocalRankingService(event -> {
                if (event.rankingId().equals("a") && event.type().equals(blockedType.get())) {
                    entered.countDown();
                    release.join();
                }
            }, RankingMetrics.NOOP);
            service.transitionSeason("b", "s", SeasonState.OPEN, "open");
            if (!eventType.equals("SEASON_OPEN")) service.transitionSeason("a", "s", SeasonState.OPEN, "open");
            if (eventType.equals("SETTLED")) {
                service.transitionSeason("a", "s", SeasonState.FROZEN, "freeze");
                service.transitionSeason("a", "s", SeasonState.SETTLING, "settling");
            }
            blockedType.set(eventType);
            var submitted = new CompletableFuture<Void>();
            Thread.ofVirtual().start(() -> {
                try {
                    switch (eventType) {
                        case "SCORE_SUBMITTED" -> service.submitScore("a", "s", "u", 1, 0, ScoreMergeMode.SET, "one");
                        case "SEASON_OPEN" -> service.transitionSeason("a", "s", SeasonState.OPEN, "open");
                        default -> service.settle("a", "s", "settled");
                    }
                    submitted.complete(null);
                } catch (Throwable failure) { submitted.completeExceptionally(failure); }
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var query = new CompletableFuture<Integer>();
                Thread.ofVirtual().start(() -> {
                    try {
                        service.seasonState("a", "s");
                        service.submitScore("b", "s", "u", 2, 0, ScoreMergeMode.SET, "other");
                        query.complete(service.queryTop("b", "s", 10).size());
                    } catch (Throwable failure) { query.completeExceptionally(failure); }
                });
                assertEquals(1, query.get(3, TimeUnit.SECONDS));
            } finally { release.complete(null); }
            submitted.get(3, TimeUnit.SECONDS);
        }
    }
}
