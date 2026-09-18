package group.zn.zero.ranking;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Synchronous, in-memory implementation; callers dispatch it through their Actor lane. */
public final class LocalRankingService implements RankingService {
    private static final int MAX_LIMIT = 100;
    private final Map<Key, Board> boards = new HashMap<>();
    private final RankingEventSink events;
    private final RankingMetrics metrics;
    public LocalRankingService() { this(RankingEventSink.NOOP, RankingMetrics.NOOP); }
    public LocalRankingService(RankingEventSink events, RankingMetrics metrics) {
        this.events = Objects.requireNonNull(events, "events"); this.metrics = Objects.requireNonNull(metrics, "metrics");
    }
    public synchronized RankingEntry submitScore(String rankingId, String seasonId, String uid, long score,
            long tieBreakValue, ScoreMergeMode mode, String idempotencyKey) {
        Key key = key(rankingId, seasonId); Objects.requireNonNull(uid, "uid"); Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey"); if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey");
        Board b = boards.computeIfAbsent(key, ignored -> new Board());
        if (b.state != SeasonState.OPEN) throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("season is " + b.state);
        RankingEntry old = b.entries.get(uid); RankingEntry result;
        if (old != null && idempotencyKey.equals(b.idempotency.get(uid))) return old;
        long merged = old == null ? score : switch (mode) { case SET -> score; case MAX -> Math.max(old.score(), score); case ADD -> addScore(old.score(), score); };
        result = new RankingEntry(uid, merged, tieBreakValue, System.currentTimeMillis(), b.version + 1);
        b.entries.put(uid, result); b.idempotency.put(uid, idempotencyKey); b.version++;
        events.onEvent(new RankingEvent("SCORE_SUBMITTED", rankingId, seasonId, uid, merged)); metrics.increment("submit", "success"); return result;
    }
    public synchronized List<RankingEntry> queryTop(String rankingId, String seasonId, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) throw RankingErrorCode.RANKING_QUERY_LIMIT_EXCEEDED.failure("limit=" + limit);
        Board b = boards.get(key(rankingId, seasonId)); if (b == null) return List.of();
        return b.entries.values().stream().sorted(comparator()).limit(limit).toList();
    }
    public synchronized Optional<PlayerRank> queryPlayerRank(String rankingId, String seasonId, String uid) {
        Objects.requireNonNull(uid, "uid"); List<RankingEntry> all = queryTop(rankingId, seasonId, MAX_LIMIT);
        Board b = boards.get(key(rankingId, seasonId)); if (b != null && all.size() < b.entries.size()) all = b.entries.values().stream().sorted(comparator()).toList();
        for (int i=0;i<all.size();i++) { RankingEntry e=all.get(i); if (e.uid().equals(uid)) return Optional.of(new PlayerRank(uid, i+1, e.score(), e.tieBreakValue())); }
        return Optional.empty();
    }
    public synchronized RankSnapshot snapshot(String rankingId, String seasonId) {
        Board b = boards.get(key(rankingId, seasonId));
        if (b == null || b.state != SeasonState.FROZEN) {
            throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("snapshot requires a frozen season");
        }
        return new RankSnapshot(rankingId,seasonId,System.currentTimeMillis(),b.version,b.entries.values().stream().sorted(comparator()).toList());
    }
    public synchronized SeasonState seasonState(String rankingId,String seasonId) { Board b=boards.get(key(rankingId,seasonId)); return b==null?SeasonState.CREATED:b.state; }
    public synchronized SeasonState transitionSeason(String rankingId,String seasonId,SeasonState target,String idempotencyKey) {
        Objects.requireNonNull(target,"target"); Objects.requireNonNull(idempotencyKey,"idempotencyKey"); Board b=boards.computeIfAbsent(key(rankingId,seasonId),ignored->new Board());
        if (idempotencyKey.equals(b.transitionKeys.get(target))) return b.state;
        if (!allowed(b.state,target)) throw RankingErrorCode.SEASON_TRANSITION_REJECTED.failure(b.state+" -> "+target);
        b.state=target; b.transitionKeys.put(target,idempotencyKey); events.onEvent(new RankingEvent("SEASON_"+target,rankingId,seasonId,null,0)); return target;
    }
    public synchronized boolean settle(String rankingId,String seasonId,String settlementId) {
        Objects.requireNonNull(settlementId,"settlementId"); Board b=boards.get(key(rankingId,seasonId));
        if (b != null && b.settlements.contains(settlementId)) return false;
        if(b==null||b.state!=SeasonState.SETTLING) throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("not settling");
        b.settlements.add(settlementId); b.state=SeasonState.SETTLED; events.onEvent(new RankingEvent("SETTLED",rankingId,seasonId,null,0)); return true;
    }
    /** 将分数溢出映射为业务拒绝，调用方尚未写入榜单。 */
    private static long addScore(long previous, long score) {
        try {
            return Math.addExact(previous, score);
        } catch (ArithmeticException failure) {
            throw RankingErrorCode.RANKING_SCORE_REJECTED.failure("score addition overflow");
        }
    }
    private static boolean allowed(SeasonState from,SeasonState to){ return (from==SeasonState.CREATED&&to==SeasonState.OPEN)||(from==SeasonState.OPEN&&to==SeasonState.FROZEN)||(from==SeasonState.FROZEN&&to==SeasonState.SETTLING)||(from==SeasonState.SETTLING&&to==SeasonState.SETTLED)||(from==SeasonState.SETTLED&&to==SeasonState.ARCHIVED)||(to==SeasonState.CANCELLED&&from!=SeasonState.SETTLED&&from!=SeasonState.ARCHIVED&&from!=SeasonState.CANCELLED); }
    private static Comparator<RankingEntry> comparator(){return Comparator.comparingLong(RankingEntry::score).reversed().thenComparingLong(RankingEntry::tieBreakValue).thenComparing(RankingEntry::uid);}
    private static Key key(String r,String s){return new Key(Objects.requireNonNull(r,"rankingId"),Objects.requireNonNull(s,"seasonId"));}
    private record Key(String rankingId,String seasonId){}
    private static final class Board { SeasonState state=SeasonState.CREATED; long version; final Map<String,RankingEntry> entries=new HashMap<>(); final Map<String,String> idempotency=new HashMap<>(); final Map<SeasonState,String> transitionKeys=new HashMap<>(); final Set<String> settlements=new HashSet<>(); }
}
