# Local world shard protocol scaffold.

client_to_server:
  enterWorld(long uid, String worldId, String shardId, int x, int y, String traceId); // Enter a local world shard.
  moveEntity(long uid, String worldId, int x, int y, String traceId); // Move entity in current shard.
  transferShard(long uid, String worldId, String targetShardId, int x, int y, String traceId); // Transfer entity to another shard.
  queryEntity(long uid, String worldId, String traceId); // Query entity shard state.
