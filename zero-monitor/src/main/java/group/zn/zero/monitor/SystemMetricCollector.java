package group.zn.zero.monitor;

import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.net.NetworkInterface;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 系统指标采集器。
 *
 * <p>该采集器只在调用 {@link #collect(MetricRegistry)} 时同步采样，不创建后台线程，
 * 适合 starter 或统一调度器按固定频率调用。
 *
 * @author zn
 */
public final class SystemMetricCollector {

    /**
     * 按稳定顺序执行的系统探针。
     */
    private static final List<SystemMetricCollectionReport.Probe> PROBES = List.of(
            SystemMetricCollectionReport.Probe.MEMORY,
            SystemMetricCollectionReport.Probe.THREAD,
            SystemMetricCollectionReport.Probe.GARBAGE_COLLECTION,
            SystemMetricCollectionReport.Probe.CPU,
            SystemMetricCollectionReport.Probe.DISK,
            SystemMetricCollectionReport.Probe.NETWORK);

    /**
     * JVM 已用内存。
     */
    public static final String JVM_MEMORY_USED = "zero_jvm_memory_used_bytes";

    /**
     * JVM 最大内存。
     */
    public static final String JVM_MEMORY_MAX = "zero_jvm_memory_max_bytes";

    /**
     * JVM 线程数。
     */
    public static final String JVM_THREAD_COUNT = "zero_jvm_thread_count";

    /**
     * JVM 已加载 class 数量。
     */
    public static final String JVM_LOADED_CLASS_COUNT = "zero_jvm_loaded_class_count";

    /**
     * JVM GC 次数。
     */
    public static final String JVM_GC_COLLECTION_TOTAL = "zero_jvm_gc_collection_total";

    /**
     * JVM GC 耗时。
     */
    public static final String JVM_GC_COLLECTION_SECONDS = "zero_jvm_gc_collection_seconds_total";

    /**
     * 进程 CPU 使用率。
     */
    public static final String PROCESS_CPU_LOAD = "zero_process_cpu_load_ratio";

    /**
     * 系统 CPU 使用率。
     */
    public static final String SYSTEM_CPU_LOAD = "zero_system_cpu_load_ratio";

    /**
     * 系统平均负载。
     */
    public static final String SYSTEM_LOAD_AVERAGE = "zero_system_load_average";

    /**
     * 磁盘可用空间。
     */
    public static final String DISK_USABLE = "zero_disk_usable_bytes";

    /**
     * 磁盘总空间。
     */
    public static final String DISK_TOTAL = "zero_disk_total_bytes";

    /**
     * 网络接口在线状态。
     */
    public static final String NETWORK_INTERFACE_UP = "zero_network_interface_up";

    /**
     * 时钟。
     */
    private final Clock clock;

    /**
     * 创建系统指标采集器。
     */
    public SystemMetricCollector() {
        this(Clock.systemUTC());
    }

    /**
     * 创建系统指标采集器。
     *
     * @param clock 时钟；不可为空。
     * @throws NullPointerException 当时钟为空时抛出。
     */
    public SystemMetricCollector(final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 注册默认系统指标定义。
     *
     * @param registry 指标注册表；不可为空。
     * @throws NullPointerException 当注册表为空时抛出。
     */
    public void registerDefaults(final MetricRegistry registry) {
        MetricRegistry current = Objects.requireNonNull(registry, "registry");
        current.register(new MetricDefinition(JVM_MEMORY_USED, "JVM used memory", "bytes", List.of("area")));
        current.register(new MetricDefinition(JVM_MEMORY_MAX, "JVM max memory", "bytes", List.of("area")));
        current.register(new MetricDefinition(
                JVM_THREAD_COUNT, "JVM live thread count", "threads", List.of("state")));
        current.register(new MetricDefinition(
                JVM_LOADED_CLASS_COUNT, "JVM loaded class count", "classes", List.of("area")));
        current.register(new MetricDefinition(
                JVM_GC_COLLECTION_TOTAL, "JVM GC collection count", "count", List.of("collector")));
        current.register(new MetricDefinition(
                JVM_GC_COLLECTION_SECONDS, "JVM GC collection time", "seconds", List.of("collector")));
        current.register(new MetricDefinition(PROCESS_CPU_LOAD, "Process CPU load ratio", "ratio", List.of()));
        current.register(new MetricDefinition(SYSTEM_CPU_LOAD, "System CPU load ratio", "ratio", List.of()));
        current.register(new MetricDefinition(SYSTEM_LOAD_AVERAGE, "System load average", "load", List.of()));
        current.register(new MetricDefinition(DISK_USABLE, "Disk usable space", "bytes", List.of("path")));
        current.register(new MetricDefinition(DISK_TOTAL, "Disk total space", "bytes", List.of("path")));
        current.register(new MetricDefinition(
                NETWORK_INTERFACE_UP, "Network interface up state", "state", List.of("name")));
    }

    /**
     * 采集系统指标。
     *
     * @param registry 指标注册表；不可为空。
     * @return 不可变采集报告；不可为空；单探针失败不会阻止后续探针。
     * @throws NullPointerException 当注册表为空时抛出。
     */
    public SystemMetricCollectionReport collect(final MetricRegistry registry) {
        MetricRegistry current = Objects.requireNonNull(registry, "registry");
        Instant now = Instant.now(clock);
        ArrayList<SystemMetricCollectionReport.ProbeFailure> failures = new ArrayList<>(2);
        int successfulProbeCount = 0;
        for (SystemMetricCollectionReport.Probe probe : PROBES) {
            if (collectProbe(probe, current, now, failures)) {
                successfulProbeCount++;
            }
        }
        return new SystemMetricCollectionReport(PROBES.size(), successfulProbeCount, failures);
    }

    private boolean collectProbe(
            final SystemMetricCollectionReport.Probe probe,
            final MetricRegistry registry,
            final Instant now,
            final List<SystemMetricCollectionReport.ProbeFailure> failures) {
        try {
            switch (probe) {
                case MEMORY -> collectMemory(registry, now);
                case THREAD -> collectThread(registry, now);
                case GARBAGE_COLLECTION -> collectGc(registry, now);
                case CPU -> collectCpu(registry, now);
                case DISK -> collectDisk(registry, now);
                case NETWORK -> collectNetwork(registry, now);
            }
            return true;
        } catch (Exception exception) {
            failures.add(SystemMetricCollectionReport.ProbeFailure.from(probe, exception));
            return false;
        }
    }

    private void collectMemory(final MetricRegistry registry, final Instant now) {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
        registry.record(sample(JVM_MEMORY_USED, heap.getUsed(), Map.of("area", "heap"), now));
        registry.record(sample(JVM_MEMORY_USED, nonHeap.getUsed(), Map.of("area", "non_heap"), now));
        registry.record(sample(JVM_MEMORY_MAX, nonNegative(heap.getMax()), Map.of("area", "heap"), now));
        registry.record(sample(JVM_MEMORY_MAX, nonNegative(nonHeap.getMax()), Map.of("area", "non_heap"), now));
        ClassLoadingMXBean classLoadingBean = ManagementFactory.getClassLoadingMXBean();
        registry.record(sample(
                JVM_LOADED_CLASS_COUNT,
                classLoadingBean.getLoadedClassCount(),
                Map.of("area", "classloader"),
                now));
    }

    private void collectThread(final MetricRegistry registry, final Instant now) {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        registry.record(sample(JVM_THREAD_COUNT, threadBean.getThreadCount(), Map.of("state", "live"), now));
        registry.record(sample(JVM_THREAD_COUNT, threadBean.getDaemonThreadCount(), Map.of("state", "daemon"), now));
    }

    private void collectGc(final MetricRegistry registry, final Instant now) {
        List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean collector : collectors) {
            Map<String, String> labels = Map.of("collector", normalizeLabel(collector.getName()));
            registry.record(sample(JVM_GC_COLLECTION_TOTAL, nonNegative(collector.getCollectionCount()), labels, now));
            registry.record(sample(
                    JVM_GC_COLLECTION_SECONDS,
                    nonNegative(collector.getCollectionTime()) / 1000D,
                    labels,
                    now));
        }
    }

    private void collectCpu(final MetricRegistry registry, final Instant now) {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        registry.record(sample(SYSTEM_LOAD_AVERAGE, nonNegative(bean.getSystemLoadAverage()), Map.of(), now));
        if (bean instanceof com.sun.management.OperatingSystemMXBean extended) {
            registry.record(sample(PROCESS_CPU_LOAD, nonNegative(extended.getProcessCpuLoad()), Map.of(), now));
            registry.record(sample(SYSTEM_CPU_LOAD, nonNegative(extended.getCpuLoad()), Map.of(), now));
        }
    }

    private void collectDisk(final MetricRegistry registry, final Instant now) throws IOException {
        for (FileStore store : FileSystems.getDefault().getFileStores()) {
            Map<String, String> labels = Map.of("path", normalizeLabel(store.toString()));
            registry.record(sample(DISK_USABLE, store.getUsableSpace(), labels, now));
            registry.record(sample(DISK_TOTAL, store.getTotalSpace(), labels, now));
        }
    }

    private void collectNetwork(final MetricRegistry registry, final Instant now) throws IOException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            Map<String, String> labels = Map.of("name", normalizeLabel(networkInterface.getName()));
            registry.record(sample(NETWORK_INTERFACE_UP, networkInterface.isUp() ? 1D : 0D, labels, now));
        }
    }

    private MetricSample sample(
            final String name,
            final double value,
            final Map<String, String> labels,
            final Instant now) {
        return new MetricSample(name, value, labels, now);
    }

    private double nonNegative(final double value) {
        return value < 0D ? 0D : value;
    }

    private long nonNegative(final long value) {
        return value < 0L ? 0L : value;
    }

    private String normalizeLabel(final String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.replace('\\', '/').replace('"', '_');
    }
}
