package group.zn.zero.monitor;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Grafana 面板模板生成器。
 *
 * <p>该生成器输出一个最小 dashboard JSON，便于 starter 或运维脚本一键写入文件后导入 Grafana。
 *
 * @author zn
 */
public final class GrafanaDashboardTemplate {

    /**
     * 创建默认 dashboard JSON。
     *
     * @param title dashboard 标题；不可为空。
     * @param metricNames 指标名称列表；不可为空；按列表顺序生成面板。
     * @return dashboard JSON；不可为空。
     * @throws NullPointerException 当标题或指标列表为空时抛出。
     */
    public String createDashboard(final String title, final List<String> metricNames) {
        String currentTitle = Objects.requireNonNull(title, "title");
        List<String> currentMetrics = List.copyOf(Objects.requireNonNull(metricNames, "metricNames"));
        String panels = currentMetrics.stream()
                .map(metric -> panelJson(currentMetrics.indexOf(metric) + 1, metric))
                .collect(Collectors.joining(","));
        return "{"
                + "\"title\":\"" + escape(currentTitle) + "\","
                + "\"schemaVersion\":39,"
                + "\"version\":1,"
                + "\"refresh\":\"10s\","
                + "\"panels\":[" + panels + "]"
                + "}";
    }

    private String panelJson(final int id, final String metric) {
        int y = (id - 1) * 8;
        return "{"
                + "\"id\":" + id + ','
                + "\"type\":\"timeseries\","
                + "\"title\":\"" + escape(metric) + "\","
                + "\"gridPos\":{\"x\":0,\"y\":" + y + ",\"w\":12,\"h\":8},"
                + "\"targets\":[{\"expr\":\"" + escape(metric) + "\",\"refId\":\"A\"}]"
                + "}";
    }

    private String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
