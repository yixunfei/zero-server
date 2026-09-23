package group.zn.zero.npc; public record TickResult(String zoneId,int candidateCount,int processedCount,int skippedCount,Outcome outcome){public enum Outcome{COMPLETED,SKIPPED,DEGRADED,CANCELLED}}
