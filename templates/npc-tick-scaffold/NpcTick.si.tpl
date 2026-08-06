# Local NPC tick protocol scaffold.

client_to_server:
  spawnNpc(long npcId, String zoneId, int x, int y, String traceId); // Spawn a local NPC.
  setBehavior(long npcId, String zoneId, String behavior, String traceId); // Change NPC behavior.
  tickZone(String zoneId, int tick, String traceId); // Advance local zone tick.
  queryNpc(long npcId, String zoneId, String traceId); // Query NPC state summary.
