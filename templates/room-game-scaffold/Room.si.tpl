# Local room protocol scaffold.

client_to_server:
  createRoom(long ownerUid, String roomId, String traceId); // Create a local room.
  joinRoom(long uid, String roomId, String traceId); // Join a local room.
  ready(long uid, String roomId, String traceId); // Mark player ready.
  startMatch(String roomId, String traceId); // Start match when ready.
  submitFrame(long uid, String roomId, int frame, int input, String traceId); // Submit a frame input.
