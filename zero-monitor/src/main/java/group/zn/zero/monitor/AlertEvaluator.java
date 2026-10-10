package group.zn.zero.monitor;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 告警规则评估器。
 *
 * @author zn
 */
public final class AlertEvaluator {

    /**
     * 告警规则列表。
     */
    private final List<AlertRule> rules;

    /**
     * 告警 sink 列表。
     */
    private final List<AlertSink> sinks;

    /**
     * 时钟。
     */
    private final Clock clock;

    /** 每条规则当前连续满足条件的起始时间。 */
    private final Map<AlertKey, Instant> activeSince = new HashMap<>();

    /** 已经发出过告警、尚未恢复的规则实例。 */
    private final Set<AlertKey> alerted = new HashSet<>();

    /** 每条当前告警的逐 sink 送达状态，实例锁保护；失败只重试尚未成功的 sink。 */
    private final Map<AlertKey, Delivery> deliveries = new HashMap<>();

    /**
     * 创建告警规则评估器。
     *
     * @param rules 告警规则；不可为空；调用方传入后会复制。
     * @param sinks 告警 sink；不可为空；调用方传入后会复制。
     */
    public AlertEvaluator(final List<AlertRule> rules, final List<AlertSink> sinks) {
        this(rules, sinks, Clock.systemUTC());
    }

    /**
     * 创建告警规则评估器。
     *
     * @param rules 告警规则；不可为空；调用方传入后会复制。
     * @param sinks 告警 sink；不可为空；调用方传入后会复制。
     * @param clock 时钟；不可为空。
     */
    public AlertEvaluator(final List<AlertRule> rules, final List<AlertSink> sinks, final Clock clock) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.rules.forEach(rule -> Objects.requireNonNull(rule, "rule"));
        this.sinks = List.copyOf(Objects.requireNonNull(sinks, "sinks"));
        this.sinks.forEach(sink -> Objects.requireNonNull(sink, "sink"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 评估指标快照并发布告警。
     *
     * @param registry 指标注册表；不可为空。
     * @return 触发的告警事件；不可为空；有序；可能为空。
     */
    public List<AlertEvent> evaluate(final MetricRegistry registry) {
        MetricRegistry current = Objects.requireNonNull(registry, "registry");
        List<MetricSample> samples = current.samples();
        List<Delivery> pending;
        synchronized (this) { pending = prepare(samples, Instant.now(clock)); }
        RuntimeException firstFailure = null;
        for (Delivery delivery : pending) {
            try { publish(delivery); }
            catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw firstFailure;
        return pending.stream().map(delivery -> delivery.event).toList();
    }

    /** 锁内仅计算本地状态；外部样本与 sink 调用均在锁外。 */
    private List<Delivery> prepare(final List<MetricSample> samples, final Instant now) {
        List<Delivery> pending = new ArrayList<>();
        for (AlertRule rule : rules) {
            AlertKey key = new AlertKey(rule.name(), rule.metricName(), rule.labels());
            MetricSample sample = latestSample(rule, samples);
            if (sample == null || !rule.condition().matches(sample.value(), rule.threshold())) {
                activeSince.remove(key);
                alerted.remove(key);
                deliveries.remove(key);
                continue;
            }
            Instant since = activeSince.computeIfAbsent(key, ignored -> now);
            if (now.isBefore(since)) {
                since = now;
                activeSince.put(key, since);
            }
            if (!now.isBefore(since.plus(rule.holdFor()))
                    && !alerted.contains(key)) {
                Delivery delivery = deliveries.computeIfAbsent(key,
                        ignored -> new Delivery(key, event(rule, sample, now), sinks.size()));
                if (!delivery.inFlight) {
                    delivery.inFlight = true;
                    pending.add(delivery);
                }
            }
        }
        return pending;
    }

    /** 单个 delivery 同时只由一个评估者发布；失败仍继续其他 sink。 */
    private void publish(final Delivery delivery) {
        RuntimeException firstFailure = null;
        try {
            for (int index = 0; index < sinks.size(); index++) {
                if (delivery.sent[index]) continue;
                try {
                    sinks.get(index).publish(delivery.event);
                    delivery.sent[index] = true;
                } catch (RuntimeException | Error failure) {
                    RuntimeException safe = group.zn.zero.core.error.ZeroException.of(
                            MonitorErrorCode.ALERT_DELIVERY_FAILED, "alert sink publication failed", failure);
                    if (firstFailure == null) firstFailure = safe;
                    else firstFailure.addSuppressed(safe);
                }
            }
        } finally {
            synchronized (this) {
                delivery.inFlight = false;
                if (firstFailure == null && deliveries.get(delivery.key) == delivery) {
                    alerted.add(delivery.key);
                    deliveries.remove(delivery.key);
                }
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    private MetricSample latestSample(final AlertRule rule, final List<MetricSample> samples) {
        MetricSample latest = null;
        for (MetricSample sample : samples) {
            if (rule.metricName().equals(sample.name())
                    && labelsMatch(rule.labels(), sample.labels())
                    && (latest == null || !sample.time().isBefore(latest.time()))) {
                latest = sample;
            }
        }
        return latest;
    }

    private boolean labelsMatch(final Map<String, String> expected, final Map<String, String> actual) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entry.getValue().equals(actual.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private AlertEvent event(final AlertRule rule, final MetricSample sample, final Instant now) {
        return new AlertEvent(
                rule.name(),
                rule.metricName(),
                rule.severity(),
                sample.value(),
                rule.threshold(),
                sample.labels(),
                now,
                rule.message());
    }

    /** 唯一标识一条规则及其标签维度。 */
    private record AlertKey(String ruleName, String metricName, Map<String, String> labels) {
    }

    /** 当前告警周期的送达收据，不跨条件恢复保留。 */
    private static final class Delivery {
        /** 告警身份。 */
        private final AlertKey key;
        /** 首次触发快照，重试保持相同事件。 */
        private final AlertEvent event;
        /** 各 sink 成功状态；同一时刻只有发布者写入。 */
        private final boolean[] sent;
        /** 由实例锁协调发布所有权。 */
        private boolean inFlight;

        private Delivery(final AlertKey key, final AlertEvent event, final int count) {
            this.key = key; this.event = event; this.sent = new boolean[count];
        }
    }
}
