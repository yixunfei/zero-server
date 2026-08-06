# Local ranking and season protocol scaffold.

client_to_server:
  submitScore(long uid, String seasonId, int score, String traceId); // Submit player score.
  queryTop(String seasonId, int limit, String traceId); // Query top ranking entries.
  queryPlayerRank(long uid, String seasonId, String traceId); // Query player rank.
  resetSeason(String seasonId, String nextSeasonId, String traceId); // Switch to next season.
