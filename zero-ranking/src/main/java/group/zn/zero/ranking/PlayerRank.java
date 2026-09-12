package group.zn.zero.ranking;

import java.util.Objects;

/** Immutable player rank result. */
public record PlayerRank(String uid, long rank, long score, long tieBreakValue) {
    public PlayerRank { Objects.requireNonNull(uid, "uid"); }
}
