# Local scene sync protocol scaffold.

client_to_server:
  enterScene(long uid, String sceneId, int x, int y, int viewRange, String traceId); // Enter scene with AOI range.
  move(long uid, String sceneId, int x, int y, String traceId); // Move scene entity.
  queryVisible(long uid, String sceneId, String traceId); // Query visible entities.
