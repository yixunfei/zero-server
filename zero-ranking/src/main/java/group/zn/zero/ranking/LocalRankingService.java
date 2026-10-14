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
 * 状态提交在服务锁内完成，事件回调在锁外执行；回调观察到的状态已经提交。
 * 回调失败向调用方传播但不撤销提交；并发通知可能乱序，顺序业务应显式串行调用。
 * @author zn
 */
public final class LocalRankingService implements RankingService {
    /** 单次 Top 查询上限。 */
    private static final int MAX_LIMIT = 100;
    /** 每个榜单保留的精确评分收据上限；满时拒绝新键以免重复副作用。 */
    private static final int REQUEST_RECEIPT_CAPACITY = 65_536;
    /** 赛季榜单目录，由服务锁保护。 */
    private final Map<Key, Board> boards = new HashMap<>();
    /** 全局已退役赛季水位，防止删除后隐式重建旧赛季。 */
    private long retiredSeason;
    /** 事件通知端口。 */
    private final RankingEventSink events;
    /** 低基数统计端口。 */
    private final RankingMetrics metrics;

    /** 创建默认无观察器的线程安全排行榜。 */
    public LocalRankingService() { this(RankingEventSink.NOOP, RankingMetrics.NOOP); }

    /**
     * 创建排行榜，不创建线程。
     * @param events 事件端口，不可为空，回调在提交后执行且不持有服务锁。
     * @param metrics 统计端口，不可为空。
     * @throws NullPointerException 参数为空。
     */
    public LocalRankingService(final RankingEventSink events, final RankingMetrics metrics) {
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** 提交带显式单调序号的评分请求。 */
    @Override public RankingEntry submitScore(final String rankingId, final String seasonId,
            final String uid, final long score, final long tieBreakValue, final ScoreMergeMode mode,
            final long sequence, final String idempotencyKey) {
        RankingEntry result;
        boolean submitted = false;
        synchronized (this) {
            Objects.requireNonNull(uid, "uid");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            if (idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey");
            Board board = board(rankingId, seasonId);
            if (board.state != SeasonState.OPEN) {
                throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("season is " + board.state);
            }
            ScoreRequestKey requestKey = new ScoreRequestKey(uid, idempotencyKey);
            if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
            if (sequence <= board.retiredRequest) {
                throw RankingErrorCode.RANKING_IDEMPOTENCY_CONFLICT.failure("request is before replay watermark");
            }
            ScoreFingerprint fingerprint = new ScoreFingerprint(score, tieBreakValue, mode);
            ScoreReceipt receipt = board.receipts.get(requestKey);
            if (receipt != null) {
                if (receipt.sequence() != sequence || !receipt.fingerprint().equals(fingerprint)) {
                    throw RankingErrorCode.RANKING_IDEMPOTENCY_CONFLICT.failure("uid=" + uid);
                }
                return receipt.result();
            }
            if (board.receipts.size() >= REQUEST_RECEIPT_CAPACITY) {
                throw RankingErrorCode.RANKING_IDEMPOTENCY_CAPACITY_EXCEEDED.failure("capacity=" + REQUEST_RECEIPT_CAPACITY);
            }
            RankingEntry previous = board.entries.get(uid);
            long merged = previous == null ? score : switch (mode) {
                case SET -> score;
                case MAX -> Math.max(previous.score(), score);
                case ADD -> addScore(previous.score(), score);
            };
            result = new RankingEntry(uid, merged, tieBreakValue, System.currentTimeMillis(), board.version + 1);
            board.index.replace(previous, result);
            board.entries.put(uid, result);
            board.receipts.put(requestKey, new ScoreReceipt(sequence, fingerprint, result));
            board.version++;
            board.top = null;
            submitted = true;
        }
        if (submitted) {
            events.onEvent(new RankingEvent("SCORE_SUBMITTED", rankingId, seasonId, uid, result.score()));
            metrics.increment("submit", "success");
        }
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
        RankSnapshot snapshot = new RankSnapshot(rankingId, seasonId, System.currentTimeMillis(), board.version,
                board.index.first(board.entries.size()));
        board.snapshotExported = true;
        return snapshot;
    }

    /** @param rankingId 榜单 ID。 @param seasonId 赛季 ID。 @param seasonSequence 赛季序号。
     * @return 当前状态；只读、线程安全，缺失按序号水位判断，详见接口契约。 */
    @Override public synchronized SeasonState seasonState(final String rankingId, final String seasonId,
            final long seasonSequence) {
        if (seasonSequence <= 0) throw new IllegalArgumentException("seasonSequence must be positive");
        Board board = boards.get(key(rankingId, seasonId));
        if (board != null && board.seasonSequence != seasonSequence) {
            throw RankingErrorCode.RANKING_IDEMPOTENCY_CONFLICT.failure("season sequence mismatch");
        }
        if (board == null && seasonSequence <= retiredSeason) return SeasonState.ARCHIVED;
        return board == null ? SeasonState.CREATED : board.state;
    }

    /** 推进赛季状态并绑定显式单调序号。 */
    @Override public SeasonState transitionSeason(final String rankingId, final String seasonId,
            final SeasonState target, final long sequence, final String idempotencyKey) {
        synchronized (this) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Key season = key(rankingId, seasonId);
            if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
            if (sequence <= retiredSeason) {
                throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("season retired");
            }
            Board board = boards.get(season);
            if (board == null) board = new Board();
            if (board.seasonSequence != 0 && board.seasonSequence != sequence) {
                throw RankingErrorCode.RANKING_IDEMPOTENCY_CONFLICT.failure("season sequence mismatch");
            }
            if (idempotencyKey.equals(board.transitionKeys.get(target))) return board.state;
            if (!allowed(board.state, target)) {
                throw RankingErrorCode.SEASON_TRANSITION_REJECTED.failure(board.state + " -> " + target);
            }
            board.seasonSequence = sequence;
            board.state = target;
            board.transitionKeys.put(target, idempotencyKey);
            boards.put(season, board);
        }
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
    @Override public boolean settle(final String rankingId, final String seasonId, final String settlementId) {
        synchronized (this) {
            Objects.requireNonNull(settlementId, "settlementId");
            Board board = boards.get(key(rankingId, seasonId));
            if (board != null && board.settlements.contains(settlementId)) return false;
            if (board == null || board.state != SeasonState.SETTLING) {
                throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("not settling");
            }
            board.settlements.add(settlementId);
            board.state = SeasonState.SETTLED;
        }
        events.onEvent(new RankingEvent("SETTLED", rankingId, seasonId, null, 0));
        return true;
    }

    private static long addScore(final long previous, final long score) {
        try { return Math.addExact(previous, score); }
        catch (ArithmeticException failure) { throw RankingErrorCode.RANKING_SCORE_REJECTED.failure("score addition overflow"); }
    }

    @Override public synchronized int acknowledgeRequestsThrough(final String rankingId, final String seasonId,
            final long sequence) {
        if (sequence <= 0 || sequence < board(rankingId, seasonId).retiredRequest) {
            throw RankingErrorCode.RANKING_IDEMPOTENCY_CONFLICT.failure("watermark regression");
        }
        Board board = board(rankingId, seasonId);
        int before = board.receipts.size();
        board.receipts.entrySet().removeIf(entry -> entry.getValue().sequence() <= sequence);
        board.retiredRequest = sequence;
        return before - board.receipts.size();
    }

    @Override public synchronized int retireSeasonsThrough(final long sequence) {
        if (sequence <= 0 || sequence < retiredSeason) throw new IllegalArgumentException("season watermark regression");
        for (var entry : boards.entrySet()) {
            Board board = entry.getValue();
            if (board.seasonSequence <= sequence && board.state != SeasonState.CANCELLED
                    && (board.state != SeasonState.ARCHIVED || !board.snapshotExported)) {
                throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("season not archived with exported snapshot");
            }
        }
        int before = boards.size();
        boards.keySet().removeIf(key -> boards.get(key).seasonSequence <= sequence);
        retiredSeason = sequence;
        return before - boards.size();
    }

    private Board board(final String rankingId, final String seasonId) {
        Board board = boards.get(key(rankingId, seasonId));
        if (board == null) throw RankingErrorCode.RANKING_SEASON_STATE_INVALID.failure("season not found");
        return board;
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
    private record ScoreRequestKey(String uid, String idempotencyKey) { }
    private record ScoreFingerprint(long score, long tieBreakValue, ScoreMergeMode mode) { }
    private record ScoreReceipt(long sequence, ScoreFingerprint fingerprint, RankingEntry result) { }
    /** 服务锁独占的赛季状态。 @author zn */
    private static final class Board {
        /** 已显式确认的请求水位，旧请求不可重做。 */
        private long retiredRequest;
        /** 首次状态转换绑定的赛季序号。 */
        private long seasonSequence;
        /** 冻结状态是否已向应用交付快照。 */
        private boolean snapshotExported;
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
        /** 每个 UID 与幂等键的精确收据。 */
        private final Map<ScoreRequestKey, ScoreReceipt> receipts = new HashMap<>();
        /** 各目标状态的幂等转换记录。 */
        private final Map<SeasonState, String> transitionKeys = new HashMap<>();
        /** 已完成结算 ID。 */
        private final Set<String> settlements = new HashSet<>();
    }
}
