package group.zn.zero.monitor;

import java.util.List;
import java.util.Objects;

/**
 * 监控运行时门面。
 *
 * <p>该门面用于一键接入本地指标注册、系统采样、Prometheus 导出、Grafana 模板和告警评估。
 * 本类不创建后台线程，生产调度由 starter 或统一线程管理模块提供。
 *
 * @author zn
 */
public final class MonitorRuntime {

    /**
     * 指标注册表。
     */
    private final InMemoryMetricRegistry registry;

    /**
     * 系统指标采集器。
     */
    private final SystemMetricCollector systemMetricCollector;

    /**
     * Prometheus 导出器。
     */
    private final PrometheusExporter prometheusExporter;

    /**
     * Grafana 模板生成器。
     */
    private final GrafanaDashboardTemplate grafanaDashboardTemplate;

    /**
     * 告警评估器。
     */
    private final AlertEvaluator alertEvaluator;

    /**
     * 创建监控运行时。
     *
     * @param registry 指标注册表；不可为空。
     * @param systemMetricCollector 系统指标采集器；不可为空。
     * @param prometheusExporter Prometheus 导出器；不可为空。
     * @param grafanaDashboardTemplate Grafana 模板生成器；不可为空。
     * @param alertEvaluator 告警评估器；不可为空。
     */
    public MonitorRuntime(
            final InMemoryMetricRegistry registry,
            final SystemMetricCollector systemMetricCollector,
            final PrometheusExporter prometheusExporter,
            final GrafanaDashboardTemplate grafanaDashboardTemplate,
            final AlertEvaluator alertEvaluator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.systemMetricCollector = Objects.requireNonNull(systemMetricCollector, "systemMetricCollector");
        this.prometheusExporter = Objects.requireNonNull(prometheusExporter, "prometheusExporter");
        this.grafanaDashboardTemplate = Objects.requireNonNull(grafanaDashboardTemplate, "grafanaDashboardTemplate");
        this.alertEvaluator = Objects.requireNonNull(alertEvaluator, "alertEvaluator");
    }

    /**
     * 创建默认监控运行时。
     *
     * @return 监控运行时；不可为空；线程安全性由内部组件声明。
     */
    public static MonitorRuntime createDefault() {
        return createDefault(List.of(), List.of());
    }

    /**
     * 创建带告警规则的默认监控运行时。
     *
     * @param rules 告警规则；不可为空。
     * @param sinks 告警 sink；不可为空。
     * @return 监控运行时；不可为空；线程安全性由内部组件声明。
     */
    public static MonitorRuntime createDefault(final List<AlertRule> rules, final List<AlertSink> sinks) {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        SystemMetricCollector collector = new SystemMetricCollector();
        collector.registerDefaults(registry);
        return new MonitorRuntime(
                registry,
                collector,
                new PrometheusExporter(),
                new GrafanaDashboardTemplate(),
                new AlertEvaluator(rules, sinks));
    }

    /**
     * 采样一次系统指标并评估告警。
     *
     * @return 系统采集报告与触发告警的不可变汇总；不可为空；线程安全。
     * @throws RuntimeException 当告警评估或 sink 发布失败时向上抛出；系统单探针失败只进入返回报告。
     */
    public MonitorCollectionResult collectOnce() {
        SystemMetricCollectionReport report = systemMetricCollector.collect(registry);
        List<AlertEvent> events = alertEvaluator.evaluate(registry);
        return new MonitorCollectionResult(report, events);
    }

    /**
     * 导出 Prometheus 文本。
     *
     * @return Prometheus 文本；不可为空；线程安全。
     */
    public String exportPrometheus() {
        return prometheusExporter.export(registry);
    }

    /**
     * 导出 Grafana dashboard 模板。
     *
     * @param title dashboard 标题；不可为空。
     * @param metricNames 指标名称；不可为空。
     * @return dashboard JSON；不可为空；线程安全。
     */
    public String exportGrafanaDashboard(final String title, final List<String> metricNames) {
        return grafanaDashboardTemplate.createDashboard(title, metricNames);
    }

    /**
     * 返回指标注册表。
     *
     * @return 指标注册表；不可为空；线程安全。
     */
    public InMemoryMetricRegistry registry() {
        return registry;
    }
}
