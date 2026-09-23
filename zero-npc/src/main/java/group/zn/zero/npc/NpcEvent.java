package group.zn.zero.npc; public record NpcEvent(String zoneId,NpcId npcId,Type type,long version,String traceId){public enum Type{SPAWNED,DESPAWNED,BEHAVIOR_CHANGED,BEHAVIOR_FAILED,TICK_SKIPPED}}
