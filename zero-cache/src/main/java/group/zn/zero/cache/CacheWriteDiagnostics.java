package group.zn.zero.cache;

import java.time.Duration;

/**
 * 物理写屏障的无敏感键诊断快照；包含排队等待，观察超时不释放屏障。
 * @param pendingWrites 已接受但尚未完成的写入数。
 * @param overdueWrites 超过观察期限仍未完成的写入数。
 * @param oldestAge 最老写入经过时长，无在途时为零。
 * @author zn
 */
public record CacheWriteDiagnostics(int pendingWrites, int overdueWrites, Duration oldestAge) { }
