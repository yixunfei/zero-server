package group.zn.zero.monitor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        List<AlertEvent> events = current.samples().stream()
                .flatMap(sample -> rules.stream()
                        .filter(rule -> matches(rule, sample))
                        .map(rule -> event(rule, sample)))
                .toList();
        for (AlertEvent event : events) {
            for (AlertSink sink : sinks) {
                sink.publish(event);
            }
        }
        return events;
    }

    private boolean matches(final AlertRule rule, final MetricSample sample) {
        return rule.metricName().equals(sample.name())
                && labelsMatch(rule.labels(), sample.labels())
                && rule.condition().matches(sample.value(), rule.threshold());
    }

    private boolean labelsMatch(final Map<String, String> expected, final Map<String, String> actual) {
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entry.getValue().equals(actual.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private AlertEvent event(final AlertRule rule, final MetricSample sample) {
        Instant now = Instant.now(clock);
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
}
