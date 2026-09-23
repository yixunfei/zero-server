package group.zn.zero.examples.rankingseason;

import group.zn.zero.ranking.LocalRankingService;
import group.zn.zero.ranking.RankSnapshot;
import group.zn.zero.ranking.RankingEntry;
import group.zn.zero.ranking.RankingService;
import group.zn.zero.ranking.ScoreMergeMode;
import group.zn.zero.ranking.SeasonState;
import java.util.List;

/** Standalone local demonstration of the forthcoming zero-ranking API. */
public final class RankingSeasonApplication {
    private RankingSeasonApplication() { }

    public static void main(final String[] args) {
        System.out.println(runDemo().marker());
    }

    public static DemoResult runDemo() {
        RankingService ranking = new LocalRankingService();
        ranking.transitionSeason("points", "season-1", SeasonState.OPEN, "open-1");
        ranking.submitScore("points", "season-1", "1001", 100, 1001, ScoreMergeMode.SET, "set-1001");
        ranking.submitScore("points", "season-1", "1002", 240, 1002, ScoreMergeMode.SET, "set-1002");
        ranking.submitScore("points", "season-1", "1003", 180, 1003, ScoreMergeMode.SET, "set-1003");
        ranking.submitScore("points", "season-1", "1001", 80, 1001, ScoreMergeMode.MAX, "max-1001");
        ranking.submitScore("points", "season-1", "1001", 20, 1001, ScoreMergeMode.ADD, "add-1001");
        ranking.submitScore("points", "season-1", "1001", 20, 1001, ScoreMergeMode.ADD, "add-1001");
        List<RankingEntry> top = ranking.queryTop("points", "season-1", 2);
        ranking.transitionSeason("points", "season-1", SeasonState.FROZEN, "freeze-1");
        RankSnapshot snapshot = ranking.snapshot("points", "season-1");
        boolean dryRun = true; // The formal API has no side-effecting dry-run call.
        ranking.transitionSeason("points", "season-1", SeasonState.SETTLING, "settling-1");
        boolean executed = ranking.settle("points", "season-1", "settlement-1");
        boolean replay = ranking.settle("points", "season-1", "settlement-1");
        ranking.transitionSeason("points", "season-1", SeasonState.ARCHIVED, "archive-1");
        return new DemoResult(ranking.seasonState("points", "season-1"), top, snapshot,
                dryRun, executed, replay);
    }

    public record DemoResult(SeasonState status, List<RankingEntry> top, RankSnapshot snapshot,
                             boolean dryRun, boolean execute, boolean replay) {
        public String marker() {
            String topText = top.stream().map(entry -> entry.uid() + ":" + entry.score()).reduce((a, b) -> a + "," + b).orElse("");
            return "ranking-season=ok|mode=local|status=" + status + "|top=" + topText
                    + "|snapshotEntries=" + snapshot.entries().size() + "|dryRun=" + dryRun
                    + "|execute=" + execute + "|replay=" + replay + "|productionReady=false";
        }
    }
}
