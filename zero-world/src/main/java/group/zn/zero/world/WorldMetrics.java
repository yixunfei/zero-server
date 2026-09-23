package group.zn.zero.world;
public interface WorldMetrics { void increment(String operation,String result,String reason); WorldMetrics NOOP=(a,b,c)->{}; }
