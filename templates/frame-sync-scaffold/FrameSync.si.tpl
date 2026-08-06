# Local frame sync protocol scaffold.

client_to_server:
  joinMatch(long uid, String matchId, String traceId); // Join a local lockstep match.
  submitInput(long uid, String matchId, int frame, int input, String traceId); // Submit frame input.
  advanceFrame(String matchId, int frame, String traceId); // Advance deterministic frame.
  querySnapshot(String matchId, String traceId); // Query latest local snapshot.
