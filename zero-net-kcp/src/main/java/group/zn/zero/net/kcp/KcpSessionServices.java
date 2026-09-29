package group.zn.zero.net.kcp;

import java.time.Duration;
import java.util.Objects;

/** 显式注入的集群资源；实例不可变，store 生命周期由组合根拥有。 @author zn */
public record KcpSessionServices(String nodeId, KcpSessionStore store, Duration lease, Duration operationTimeout) {
    /** 校验执行期限；禁止无限保留失联 owner，非法组合在启动前拒绝。 */
    public KcpSessionServices {
        KcpSessionOwner.requireIdentity(1, nodeId, 1); Objects.requireNonNull(store);
        requireLease(lease); Objects.requireNonNull(operationTimeout);
        if (operationTimeout.toMillis() < 10 || operationTimeout.toMillis() * 3 >= lease.toMillis()) {
            throw new IllegalArgumentException("ownership operation timeout must be < lease / 3");
        }
    }
    /** @return 独立本地所有权服务，不创建线程，不共享全局状态。 */
    public static KcpSessionServices local() {
        return new KcpSessionServices("local", new InMemoryKcpSessionStore(), Duration.ofSeconds(30), Duration.ofSeconds(3));
    }
    /** @param lease 租约；有效区间 100ms..5min，非法值抛出参数异常。 */
    public static void requireLease(final Duration lease) {
        Objects.requireNonNull(lease);
        if (lease.toMillis() < 100 || lease.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("invalid KCP lease");
    }
}
