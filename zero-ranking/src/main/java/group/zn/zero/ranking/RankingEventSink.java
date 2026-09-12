package group.zn.zero.ranking;

/** Ranking lifecycle and settlement notifications. */
public interface RankingEventSink {
    void onEvent(RankingEvent event);
    RankingEventSink NOOP = event -> { };
}
