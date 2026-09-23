package group.zn.zero.ranking;

import java.util.Objects;

/** Immutable ranked entry. */
public record RankingEntry(String uid, long score, long tieBreakValue, long updatedAt, long version) {
    public RankingEntry {
        Objects.requireNonNull(uid, "uid");
        if (uid.isBlank()) throw new IllegalArgumentException("uid must not be blank");
    }
}
