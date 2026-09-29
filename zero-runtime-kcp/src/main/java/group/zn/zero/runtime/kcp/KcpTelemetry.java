package group.zn.zero.runtime.kcp;

import group.zn.zero.monitor.MetricDefinition;
import group.zn.zero.monitor.MetricRegistry;
import group.zn.zero.monitor.MetricSample;
import group.zn.zero.net.kcp.KcpSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** 低基数 KCP 指标桥接，不含 conv、主体、地址或票据；线程安全性由 registry 保证。 @author zn */
public final class KcpTelemetry implements Consumer<KcpSnapshot> {
    /** 指标注册表。 */
    private final MetricRegistry registry;
    /** 固定监听器标签。 */
    private final Map<String, String> labels;
    /**
     * 注册固定指标定义；重复相同定义由 registry 幂等处理。
     * @param listener 配置级监听器名。 @param registry 框架 registry。
     */
    public KcpTelemetry(final String listener, final MetricRegistry registry) {
        this.registry = Objects.requireNonNull(registry); labels = Map.of("listener", listener);
        for (String name : List.of("sessions", "connected", "received_total", "sent_total", "rejected_total", "failures_total",
                "updates_total", "flushes_total", "pending_send_bytes", "pending_inbound_bytes")) {
            registry.register(new MetricDefinition("zero_kcp_" + name, "KCP " + name, "count", List.of("listener")));
        }
        registry.register(new MetricDefinition("zero_kcp_reason_total", "KCP fixed reason counts", "count", List.of("listener", "reason")));
    }
    /** @param snapshot 独立快照；在框架后台执行域采样，不阻塞 IO，不修改业务数据。 */
    @Override public void accept(final KcpSnapshot snapshot) {
        record("sessions", snapshot.sessions()); record("connected", snapshot.connected());
        record("received_total", snapshot.receivedDatagrams()); record("sent_total", snapshot.sentDatagrams());
        record("rejected_total", snapshot.rejectedDatagrams()); record("failures_total", snapshot.failures());
        record("updates_total", snapshot.updates()); record("flushes_total", snapshot.flushes());
        record("pending_send_bytes", snapshot.pendingSendBytes()); record("pending_inbound_bytes", snapshot.pendingInboundBytes());
        snapshot.reasons().forEach((reason, count) -> registry.record(new MetricSample("zero_kcp_reason_total", count,
                Map.of("listener", labels.get("listener"), "reason", reason), Instant.now())));
    }
    private void record(final String name, final long value) {
        registry.record(new MetricSample("zero_kcp_" + name, value, labels, Instant.now()));
    }
}
