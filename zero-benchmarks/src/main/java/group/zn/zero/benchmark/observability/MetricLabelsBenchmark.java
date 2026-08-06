package group.zn.zero.benchmark.observability;

import group.zn.zero.monitor.InMemoryMetricRegistry;
import group.zn.zero.monitor.MetricDefinition;
import group.zn.zero.monitor.MetricSample;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * 指标标签数量的样本创建与注册表校验开销基准。
 *
 * <p>每次调用从固定标签输入创建真实 {@link MetricSample}，并写入已注册相同有序 schema 的
 * {@link InMemoryMetricRegistry}。invocation teardown 只清空样本、保留定义，避免样本集合增长；
 * 基准不启动线程、不执行外部 IO，也不代表生产时序基数或远程导出性能。
 *
 * @author zn
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MetricLabelsBenchmark {

    /**
     * 固定采样时间，避免把系统时钟读取计入测量。
     */
    private static final Instant SAMPLE_TIME = Instant.parse("2026-08-04T00:00:00Z");

    /**
     * 固定指标名。
     */
    private static final String METRIC_NAME = "zero_benchmark_observation_total";

    /**
     * 标签数量；由 JMH 注入，只允许指标契约内的三个受控值。
     */
    @Param({"0", "4", "8"})
    public int labelCount;

    /**
     * 固定、不可变的样本标签；仅由当前 benchmark 线程读取。
     */
    private Map<String, String> labels;

    /**
     * 真实内存指标注册表；定义常驻，样本按 invocation 清空。
     */
    private InMemoryMetricRegistry metricRegistry;

    /**
     * 为单个 JMH trial 创建固定标签、定义和真实注册表。
     *
     * <p>该方法只修改当前 benchmark 线程私有状态，不创建线程、不执行外部 IO。
     *
     * @throws IllegalArgumentException JMH 注入未声明的标签数量时抛出。
     */
    @Setup(Level.Trial)
    public void setUp() {
        List<String> labelNames = createLabelNames(labelCount);
        labels = createLabels(labelNames);
        metricRegistry = new InMemoryMetricRegistry();
        metricRegistry.register(new MetricDefinition(
                METRIC_NAME,
                "Benchmark observation count",
                "count",
                labelNames));
    }

    /**
     * 创建并记录一条具有受控标签数量的指标样本。
     *
     * <p>该方法会向线程私有注册表增加一条样本，不修改固定标签输入；样本由 teardown 清理。
     *
     * @return 本次创建并成功记录的不可变指标样本；不可为空且线程安全。
     */
    @Benchmark
    public MetricSample record() {
        MetricSample sample = new MetricSample(METRIC_NAME, 1D, labels, SAMPLE_TIME);
        metricRegistry.record(sample);
        return sample;
    }

    /**
     * 在每次 benchmark 调用后清空样本并保留指标定义。
     *
     * <p>清理发生在计时范围外，只修改当前线程私有注册表状态。
     */
    @TearDown(Level.Invocation)
    public void clearSamples() {
        metricRegistry.clearSamples();
    }

    /**
     * 创建受控数量的有序标签名。
     *
     * @param count 标签数量；只允许 0、4 或 8。
     * @return 不可变、有序、可能为空、线程安全的标签名列表；零标签时为空。
     * @throws IllegalArgumentException 标签数量不受支持时抛出。
     */
    private List<String> createLabelNames(final int count) {
        if (count != 0 && count != 4 && count != 8) {
            throw new IllegalArgumentException("unsupported labelCount: " + count);
        }
        if (count == 0) {
            return List.of();
        }
        List<String> names = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            names.add("dimension" + index);
        }
        return List.copyOf(names);
    }

    /**
     * 按定义 schema 创建固定标签值。
     *
     * @param labelNames 有序标签名；不可为空。
     * @return 不可变、有序、可能为空、线程安全的标签 Map；零标签时为空。
     */
    private Map<String, String> createLabels(final List<String> labelNames) {
        if (labelNames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> created = new LinkedHashMap<>(labelNames.size());
        for (int index = 0; index < labelNames.size(); index++) {
            created.put(labelNames.get(index), "value-" + index);
        }
        return Collections.unmodifiableMap(created);
    }
}
