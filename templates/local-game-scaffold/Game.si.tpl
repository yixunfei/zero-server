# Local game protocol scaffold.

client_to_server:
  login(String accountId, String token, String traceId); // Login with local demo account.
  enterScene(long uid, String sceneId, String traceId); // Enter demo scene.
  move(long uid, String sceneId, int x, int y, String traceId); // Move in demo scene.
