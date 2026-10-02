package group.zn.zero.world;
/** 世界指标观察接口；实现须线程安全、非阻塞，不等待实体 lane。 @author zn */
public interface WorldMetrics { void increment(String operation,String result,String reason); WorldMetrics NOOP=(a,b,c)->{}; }
