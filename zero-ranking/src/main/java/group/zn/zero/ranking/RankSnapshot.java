package group.zn.zero.ranking;

import java.util.List;
import java.util.Objects;

/** Immutable ranking snapshot. */
public record RankSnapshot(String rankingId, String seasonId, long generatedAt, long version,
                           List<RankingEntry> entries) {
    public RankSnapshot {
        Objects.requireNonNull(rankingId, "rankingId");
        Objects.requireNonNull(seasonId, "seasonId");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
