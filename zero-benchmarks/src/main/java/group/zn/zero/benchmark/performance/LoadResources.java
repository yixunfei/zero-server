package group.zn.zero.benchmark.performance;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;

/** 独立负载进程资源采样；不把 committed virtual memory 当 RSS。 @author zn */
final class LoadResources {
    private LoadResources() { }
    /** 输出 JSONL；JVM 指标跨线程观察，不能视作原子快照。 */
    static void sample(final String role) {
        Runtime runtime = Runtime.getRuntime();
        long direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct")).mapToLong(BufferPoolMXBean::getMemoryUsed).sum();
        long gcMillis = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long fd = os instanceof com.sun.management.UnixOperatingSystemMXBean unix ? unix.getOpenFileDescriptorCount() : -1;
        System.out.printf(java.util.Locale.ROOT,
                "{\"kind\":\"resource\",\"role\":\"%s\",\"timeMillis\":%d,\"pid\":%d,\"heapUsed\":%d,"
                + "\"directBytes\":%d,\"gcMillis\":%d,\"cpuNanos\":%d,\"threads\":%d,\"fd\":%d}%n",
                role, System.currentTimeMillis(), ProcessHandle.current().pid(), runtime.totalMemory() - runtime.freeMemory(),
                direct, gcMillis, os.getProcessCpuTime(), ManagementFactory.getThreadMXBean().getThreadCount(), fd);
    }
}
