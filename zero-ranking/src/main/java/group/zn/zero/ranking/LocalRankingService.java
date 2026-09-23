package group.zn.zero.ranking;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 同步内存排行榜；线程安全，服务锁同时保护 UID 表、排名索引和赛季状态。
 * 保留锁内事件回调顺序；索引不改变回调重入语义，调用方仍可通过 Actor Lane 编排操作。
 * @author zn
 */
public final class LocalRankingService implements RankingService {
    /** 单次 Top 查询上限。 */
    private static final int MAX_LIMIT = 100;
    /** 赛季榜单目录，由服务锁保护。 */
    private final Map<Key, Board> boards = new HashMap<>();
    /** 事件通知端口。 */
    private final RankingEventSink events;
    /** 低基数统计端口。 */
    private final RankingMetrics metrics;

    /** 创建默认无观察器的线程安全排行榜。 */
    public LocalRankingService() { this(RankingEventSink.NOOP, RankingMetrics.NOOP); }

    /**
     * 创建排行榜，不创建线程。
     * @param events 事件端口，不可为空，回调在服务锁内执行。
     * @param metrics 统计端口，不可为空。
     * @throws NullPointerException 参数为空。
     */
    public LocalRankingService(final RankingEventSink events, final RankingMetrics metrics) {
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * 合并分数并同步更新 UID/排序索引及版本；线程安全，同 UID 最近幂等键重复时返回旧值。
     * @param rankingId 榜单 ID，不可为空。
     * @param seasonId 赛季 ID，不可为空。
     * @param uid 玩家 ID，不可为空或空白。
     * @param score 新分数或增量。
     * @param tieBreakValue 同分时升序比较的值。
     * @param mode 合并策略，不可为空。
     * @param idempotencyKey 幂等键，不可为空或空白。
     * @return 不可变条目，可跨线程共享。
     * @throws group.zn.zero.core.error.ZeroException 赛季不接受更新或 ADD 溢出；拒绝不变更索引。
     * @throws IllegalArgumentException UID/幂等键为空白。
     * @throws NullPointerException 必填参数为空。
     */
    @Override public synchronized RankingEntry submitScore(final String rankingId, final String seasonId,
            final String uid, final long score, final long tieBreakValue, final ScoreMergeMode mode,
            final String idempotencyKey) {
        Key key = key(rankingId, seasonId);
        Objects.requireNonNull(uid, "uid");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey");
        Board board = boards.computeIfAbsent(key, ignored -> new Board());
        if (board.state != SeasonState.OPEN) {
            throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("season is " + board.state);
        }
        RankingEntry previous = board.entries.get(uid);
        if (previous != null && idempotencyKey.equals(board.idempotency.get(uid))) return previous;
        long merged = previous == null ? score : switch (mode) {
            case SET -> score;
            case MAX -> Math.max(previous.score(), score);
            case ADD -> addScore(previous.score(), score);
        };
        RankingEntry result = new RankingEntry(uid, merged, tieBreakValue, System.currentTimeMillis(), board.version + 1);
        board.index.replace(previous, result);
        board.entries.put(uid, result);
        board.idempotency.put(uid, idempotencyKey);
        board.version++;
        board.top = null;
        events.onEvent(new RankingEvent("SCORE_SUBMITTED", rankingId, seasonId, uid, merged));
        metrics.increment("submit", "success");
        return result;
    }

    /**
     * 按索引顺序读取 Top K，不全量排序；只读，线程安全。
     * @param rankingId 榜单 ID。
     * @param seasonId 赛季 ID。
     * @param limit 1..100。
     * @return 不可变有序列表，可为空，可跨线程共享。
     * @throws group.zn.zero.core.error.ZeroException limit 超限。
     */
    @Override public synchronized List<RankingEntry> queryTop(final String rankingId, final String seasonId, final int limit) {
        if (limit < 1 || limit > MAX_LIMIT) throw RankingErrorCode.RANKING_QUERY_LIMIT_EXCEEDED.failure("limit=" + limit);
        Board board = boards.get(key(rankingId, seasonId));
        if (board == null) return List.of();
        if (board.top == null || board.topLimit != limit) {
            board.top = board.index.first(limit);
            board.topLimit = limit;
        }
        return board.top;
    }

    /**
     * 查询玩家排名；缺失 UID 不遍历索引，只读、线程安全。
     * @param rankingId 榜单 ID。
     * @param seasonId 赛季 ID。
     * @param uid 玩家 ID，不可为空。
     * @return 不可变排名 Optional，缺失时 empty。
     * @throws NullPointerException 必填参数为空。
     */
    @Override public synchronized Optional<PlayerRank> queryPlayerRank(final String rankingId, final String seasonId,
            final String uid) {
        Objects.requireNonNull(uid, "uid");
        Board board = boards.get(key(rankingId, seasonId));
        RankingEntry entry = board == null ? null : board.entries.get(uid);
        return entry == null ? Optional.empty() : Optional.of(new PlayerRank(
                uid, board.index.rank(entry), entry.score(), entry.tieBreakValue()));
    }

    /**
     * 顺序导出冻结榜单，只读、线程安全。
     * @param rankingId 榜单 ID。
     * @param seasonId 赛季 ID。
     * @return 不可变全量有序快照，可为空，可跨线程共享。
     * @throws group.zn.zero.core.error.ZeroException 赛季未冻结。
     */
    @Override public synchronized RankSnapshot snapshot(final String rankingId, final String seasonId) {
        Board board = boards.get(key(rankingId, seasonId));
        if (board == null || board.state != SeasonState.FROZEN) {
            throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("snapshot requires a frozen season");
        }
        return new RankSnapshot(rankingId, seasonId, System.currentTimeMillis(), board.version,
                board.index.first(board.entries.size()));
    }

    /** @param rankingId 榜单 ID。 @param seasonId 赛季 ID。 @return 当前状态；只读、线程安全，缺失为 CREATED。 */
    @Override public synchronized SeasonState seasonState(final String rankingId, final String seasonId) {
        Board board = boards.get(key(rankingId, seasonId));
        return board == null ? SeasonState.CREATED : board.state;
    }

    /**
     * 幂等推进赛季状态并通知观察器；线程安全。
     * @param rankingId 榜单 ID。
     * @param seasonId 赛季 ID。
     * @param target 目标状态，不可为空。
     * @param idempotencyKey 转换幂等键，不可为空。
     * @return 变更后的赛季状态。
     * @throws group.zn.zero.core.error.ZeroException 状态转换非法。
     */
    @Override public synchronized SeasonState transitionSeason(final String rankingId, final String seasonId,
            final SeasonState target, final String idempotencyKey) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Board board = boards.computeIfAbsent(key(rankingId, seasonId), ignored -> new Board());
        if (idempotencyKey.equals(board.transitionKeys.get(target))) return board.state;
        if (!allowed(board.state, target)) throw RankingErrorCode.SEASON_TRANSITION_REJECTED.failure(board.state + " -> " + target);
        board.state = target;
        board.transitionKeys.put(target, idempotencyKey);
        events.onEvent(new RankingEvent("SEASON_" + target, rankingId, seasonId, null, 0));
        return target;
    }

    /**
     * 登记结算并通知观察器；线程安全。
     * @param rankingId 榜单 ID。
     * @param seasonId 赛季 ID。
     * @param settlementId 结算幂等 ID，不可为空。
     * @return 首次结算 true，重复 false。
     * @throws group.zn.zero.core.error.ZeroException 不处于 SETTLING。
     */
    @Override public synchronized boolean settle(final String rankingId, final String seasonId, final String settlementId) {
        Objects.requireNonNull(settlementId, "settlementId");
        Board board = boards.get(key(rankingId, seasonId));
        if (board != null && board.settlements.contains(settlementId)) return false;
        if (board == null || board.state != SeasonState.SETTLING) {
            throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("not settling");
        }
        board.settlements.add(settlementId);
        board.state = SeasonState.SETTLED;
        events.onEvent(new RankingEvent("SETTLED", rankingId, seasonId, null, 0));
        return true;
    }

    private static long addScore(final long previous, final long score) {
        try { return Math.addExact(previous, score); }
        catch (ArithmeticException failure) { throw RankingErrorCode.RANKING_SCORE_REJECTED.failure("score addition overflow"); }
    }
    private static boolean allowed(final SeasonState from, final SeasonState to) {
        return (from == SeasonState.CREATED && to == SeasonState.OPEN)
                || (from == SeasonState.OPEN && to == SeasonState.FROZEN)
                || (from == SeasonState.FROZEN && to == SeasonState.SETTLING)
                || (from == SeasonState.SETTLING && to == SeasonState.SETTLED)
                || (from == SeasonState.SETTLED && to == SeasonState.ARCHIVED)
                || (to == SeasonState.CANCELLED && from != SeasonState.SETTLED
                        && from != SeasonState.ARCHIVED && from != SeasonState.CANCELLED);
    }
    private static Key key(final String ranking, final String season) {
        return new Key(Objects.requireNonNull(ranking, "rankingId"), Objects.requireNonNull(season, "seasonId"));
    }
    /** 结构化目录键，避免分隔符歧义。 @author zn */
    private record Key(String rankingId, String seasonId) { }
    /** 服务锁独占的赛季状态。 @author zn */
    private static final class Board {
        /** 默认新赛季。 */
        private SeasonState state = SeasonState.CREATED;
        /** 已成功提交次数。 */
        private long version;
        /** 同一版本最近一次 Top 查询；最多 100 个引用，写提交时立即失效。 */
        private List<RankingEntry> top;
        /** 缓存的请求上限。 */
        private int topLimit;
        /** 排名索引，与 UID 表同步更新。 */
        private final RankIndex index = new RankIndex();
        /** UID 精确查找。 */
        private final Map<String, RankingEntry> entries = new HashMap<>();
        /** 各 UID 的最近幂等键。 */
        private final Map<String, String> idempotency = new HashMap<>();
        /** 各目标状态的幂等转换记录。 */
        private final Map<SeasonState, String> transitionKeys = new HashMap<>();
        /** 已完成结算 ID。 */
        private final Set<String> settlements = new HashSet<>();
    }
}
