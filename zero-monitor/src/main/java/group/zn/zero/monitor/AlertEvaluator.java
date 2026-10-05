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
    public synchronized List<AlertEvent> evaluate(final MetricRegistry registry) {
        MetricRegistry current = Objects.requireNonNull(registry, "registry");
        List<MetricSample> samples = current.samples();
        List<AlertEvent> events = new ArrayList<>();
        List<AlertKey> eventKeys = new ArrayList<>();
        Set<AlertKey> scheduled = new HashSet<>();
        for (AlertRule rule : rules) {
            AlertKey key = new AlertKey(rule.name(), rule.metricName(), rule.labels());
            MetricSample sample = latestSample(rule, samples);
            if (sample == null || !rule.condition().matches(sample.value(), rule.threshold())) {
                activeSince.remove(key);
                alerted.remove(key);
                continue;
            }
            Instant since = activeSince.computeIfAbsent(key, ignored -> sample.time());
            if (sample.time().isBefore(since)) {
                since = sample.time();
                activeSince.put(key, since);
            }
            Instant now = Instant.now(clock);
            if (!now.isBefore(since.plus(rule.holdFor()))
                    && !alerted.contains(key) && scheduled.add(key)) {
                events.add(event(rule, sample, now));
                eventKeys.add(key);
            }
        }
        for (int index = 0; index < events.size(); index++) {
            AlertEvent event = events.get(index);
            for (AlertSink sink : sinks) {
                sink.publish(event);
            }
            alerted.add(eventKeys.get(index));
        }
        return events;
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
}
