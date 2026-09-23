package group.zn.zero.ranking;

/** Low-cardinality ranking metrics SPI. */
public interface RankingMetrics {
    void increment(String operation, String result);
    RankingMetrics NOOP = (operation, result) -> { };
}
