package group.zn.zero.net.kcp;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 服务独占低基数指标，允许非 IO 线程读取。 @author zn */
final class KcpCounters {
    /** 有效授权数。 */
    final AtomicLong sessions = new AtomicLong();
    /** 绑定数。 */
    final AtomicLong connected = new AtomicLong();
    /** 入站包。 */
    final AtomicLong received = new AtomicLong();
    /** 出站包。 */
    final AtomicLong sent = new AtomicLong();
    /** 算法更新次数。 */
    final AtomicLong updates = new AtomicLong();
    /** socket 刷新次数。 */
    final AtomicLong flushes = new AtomicLong();
    /** 原因均由框架固定常量给出，不能放主体标签。 */
    private final Map<String, AtomicLong> reasons = new ConcurrentHashMap<>();
    void reason(final String value) { reasons.computeIfAbsent(value, ignored -> new AtomicLong()).incrementAndGet(); }
    Map<String, Long> reasons() {
        Map<String, Long> result = new TreeMap<>();
        reasons.forEach((key, value) -> result.put(key, value.get()));
        return result;
    }
}
