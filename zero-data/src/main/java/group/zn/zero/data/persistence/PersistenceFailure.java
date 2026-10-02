package group.zn.zero.data.persistence;

/** 持久化失败诊断；不保留异常栈或业务快照，线程安全。 @author zn */
public record PersistenceFailure(String targetName, Object id, int attempts, long nextAttemptAt,
                                 boolean quarantined, String errorCode) { }
