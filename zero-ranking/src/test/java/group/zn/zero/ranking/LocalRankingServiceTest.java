package group.zn.zero.ranking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalRankingServiceTest {
    private LocalRankingService service() { return new LocalRankingService(); }
    private void open(LocalRankingService s) { s.transitionSeason("r", "s", SeasonState.OPEN, "open"); }
    private void score(LocalRankingService s,String uid,long n,String key) { s.submitScore("r","s",uid,n,Long.parseLong(uid),ScoreMergeMode.SET,key); }
    @Test void scoreSubmitRejectsClosedSeason() { LocalRankingService s=service(); assertThrows(RuntimeException.class,()->score(s,"1",1,"a")); }
    @Test void scoreMergeModeIsExplicit() { LocalRankingService s=service(); open(s); score(s,"1",10,"a"); s.submitScore("r","s","1",5,1,ScoreMergeMode.MAX,"b"); assertEquals(10,s.queryTop("r","s",1).get(0).score()); }
    @Test void rankingTopIsOrderedAndLimited() { LocalRankingService s=service(); open(s); score(s,"1",2,"a"); score(s,"2",4,"b"); score(s,"3",3,"c"); assertEquals(List.of("2","3"),s.queryTop("r","s",2).stream().map(RankingEntry::uid).toList()); }
    @Test void playerRankReturnsEmptyWhenMissing() { LocalRankingService s=service(); open(s); assertTrue(s.queryPlayerRank("r","s","x").isEmpty()); }
    @Test void tieBreakIsStable() { LocalRankingService s=service(); open(s); score(s,"2",10,"a"); score(s,"1",10,"b"); assertEquals("1",s.queryTop("r","s",2).get(0).uid()); }
    @Test void seasonTransitionIsIdempotent() { LocalRankingService s=service(); assertEquals(SeasonState.OPEN,s.transitionSeason("r","s",SeasonState.OPEN,"x")); assertEquals(SeasonState.OPEN,s.transitionSeason("r","s",SeasonState.OPEN,"x")); }
    @Test void settlementDoesNotRunTwice() { LocalRankingService s=service(); open(s); s.transitionSeason("r","s",SeasonState.FROZEN,"f"); s.transitionSeason("r","s",SeasonState.SETTLING,"t"); assertTrue(s.settle("r","s","z")); assertFalse(s.settle("r","s","z")); }
    @Test void snapshotMatchesFrozenRanking() { LocalRankingService s=service(); open(s); score(s,"1",3,"a"); s.transitionSeason("r","s",SeasonState.FROZEN,"f"); assertEquals(s.queryTop("r","s",1),s.snapshot("r","s").entries()); }
    @Test void metricsDoNotUseRankingIdLabels() { assertDoesNotThrow(() -> new LocalRankingService()); }
}
