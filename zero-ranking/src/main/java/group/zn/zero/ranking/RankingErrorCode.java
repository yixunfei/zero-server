package group.zn.zero.ranking;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.ZeroException;

/** Stable errors for ranking operations. */
public enum RankingErrorCode implements ErrorCode {
    RANKING_SEASON_STATE_INVALID("Season state does not allow this operation"),
    RANKING_SCORE_REJECTED("Score was rejected"),
    RANKING_QUERY_LIMIT_EXCEEDED("Query limit exceeded"),
    SEASON_TRANSITION_REJECTED("Season transition rejected");
    private final String message;
    RankingErrorCode(String message) { this.message = message; }
    public ErrorCategory category() { return ErrorCategory.CLIENT_REQUEST; }
    public String code() { return name(); }
    public String message() { return message; }
    public ZeroException failure(String detail) { return new ZeroException(this, detail); }
}
