package group.zn.zero.ranking;

import java.util.Objects;

/** Immutable event emitted by the local ranking slice. */
public record RankingEvent(String type, String rankingId, String seasonId, String uid, long score) {
    public RankingEvent { Objects.requireNonNull(type, "type"); Objects.requireNonNull(rankingId, "rankingId"); Objects.requireNonNull(seasonId, "seasonId"); }
}
