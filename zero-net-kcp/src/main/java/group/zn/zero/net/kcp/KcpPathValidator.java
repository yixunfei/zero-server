package group.zn.zero.net.kcp;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 候选 UDP 路径状态机；认证之后才可调用，挑战限流且不放大，线程安全。 @author zn */
public final class KcpPathValidator {
    /** 固定随机挑战载荷，同时为探测最小填充长度。 */
    public static final int CHALLENGE_BYTES = 24;
    /** 有界路径策略。 */
    private final KcpPathOptions options;
    /** token 随机源。 */
    private final SecureRandom random = new SecureRandom();
    /** 待验证路径。 */
    private final Map<InetSocketAddress, Candidate> candidates = new HashMap<>();
    /** 旧地址短期排空，不允许其挑战切回。 */
    private final Map<InetSocketAddress, Long> retired = new LinkedHashMap<>();
    /** 当前路由。 */
    private InetSocketAddress active;
    /** 下一次允许新建候选的单调时间，抵抗地址轮换。 */
    private long nextCandidate;
    /** @param options 已校验策略；不创建线程或 socket。 */
    public KcpPathValidator(final KcpPathOptions options) { this.options = Objects.requireNonNull(options); }
    /** 首次认证后绑定；参数非空，之后不覆盖路由；线程安全。 */
    public synchronized void bind(final InetSocketAddress address) { if (active == null) active = Objects.requireNonNull(address); }
    /** @return 当前已验证地址，未绑定为空；线程安全。 */
    public synchronized InetSocketAddress active() { return active; }
    /** @return 是否允许在当前/旧排空路径接收；不改变路由，线程安全。 */
    public synchronized boolean accepts(final InetSocketAddress address, final long now) {
        expire(now); return address.equals(active) || retired.containsKey(address);
    }
    /**
     * 为已认证候选生成挑战，重试间隔内和预算不足时返回 null；响应字节不超过触发包。
     * @param address 候选地址。 @param now 单调时间。 @param receivedBytes 触发包大小。
     * @param responseBytes 拟发送包大小。 @return token 副本或 null；更新有界候选状态，线程安全。
     */
    public synchronized byte[] challenge(final InetSocketAddress address, final long now,
            final int receivedBytes, final int responseBytes) {
        expire(now);
        if (!options.enabled() || address.equals(active) || retired.containsKey(address)
                || receivedBytes < responseBytes || responseBytes <= 0) return null;
        Candidate candidate = candidates.get(address);
        if (candidate == null) {
            if (candidates.size() >= options.maxCandidates() || now < nextCandidate) return null;
            byte[] token = new byte[CHALLENGE_BYTES]; random.nextBytes(token);
            candidate = new Candidate(token, now + options.timeout().toNanos()); candidates.put(address, candidate);
            nextCandidate = now + options.retryInterval().toNanos();
        }
        if (now < candidate.nextSend) return null;
        candidate.nextSend = now + options.retryInterval().toNanos(); return candidate.token.clone();
    }
    /**
     * 验证同一地址 token；成功后切换路由并清除其他候选，旧地址有界排空。
     * @return 是否迁移成功；无效响应不改变路由，线程安全。
     */
    public synchronized boolean confirm(final InetSocketAddress address, final byte[] token, final long now) {
        expire(now); Candidate candidate = candidates.get(address);
        if (candidate == null || token == null || !MessageDigest.isEqual(candidate.token, token)) return false;
        if (active != null) {
            if (retired.size() >= options.maxCandidates()) retired.remove(retired.keySet().iterator().next());
            retired.put(active, now + options.retiredPathLifetime().toNanos());
        }
        active = address; candidates.clear(); return true;
    }
    /** @return 清理超时候选数；线程安全，同时清理旧路径。 */
    public synchronized int expire(final long now) {
        int previous = candidates.size();
        candidates.entrySet().removeIf(entry -> now >= entry.getValue().expires);
        retired.entrySet().removeIf(entry -> now >= entry.getValue());
        return previous - candidates.size();
    }
    /** 有界候选记录。 @author zn */
    private static final class Candidate {
        /** 随机 token。 */
        private final byte[] token;
        /** 不因重试延长的期限。 */
        private final long expires;
        /** 下次允许发送。 */
        private long nextSend;
        Candidate(final byte[] token, final long expires) { this.token = token; this.expires = expires; }
    }
}
