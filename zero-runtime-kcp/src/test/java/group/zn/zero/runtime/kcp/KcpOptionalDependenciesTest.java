package group.zn.zero.runtime.kcp;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.net.kcp.KcpProfile;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 隔离消费者类路径，防止可选依赖被模块自身测试类路径掩盖。 @author zn */
class KcpOptionalDependenciesTest {

    /** 无任何 zero 日志/监控 jar 时，KCP 模块仍能装配、绑定端口并关闭受管资源。 */
    @Test
    @Timeout(10)
    void startsWithoutObservabilityJars() throws Exception {
        List<URL> urls = new ArrayList<>();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (!observabilityEntry(entry)) urls.add(Path.of(entry).toUri().toURL());
        }
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("group.zn.zero.monitor.MetricRegistry"));
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("group.zn.zero.runtime.monitor.MonitorRuntimeComponent"));
            Class<?> probe = loader.loadClass(MinimalConsumer.class.getName());
            assertTrue((int) probe.getMethod("startAndClose").invoke(null) > 0);
        }
    }

    private static boolean observabilityEntry(final String entry) {
        String normalized = entry.replace(File.separatorChar, '/');
        return List.of("zero-log", "zero-monitor", "zero-runtime-log", "zero-runtime-monitor").stream()
                .anyMatch(module -> normalized.contains("/" + module + "/")
                        || normalized.matches(".*/" + module + "-[0-9].*[.]jar"));
    }

    /** 由隔离加载器运行的实际消费者，不引用监控类型或 JUnit。 @author zn */
    public static final class MinimalConsumer {
        private MinimalConsumer() { }

        /**
         * 创建最小 KCP 监听器并在返回前关闭全部受管资源。
         * @return OS 分配的端口；正数代表成功启动；各次调用资源独立。
         * @throws RuntimeException 装配、绑定或关闭失败时抛出，不改变外部资源。
         */
        public static int startAndClose() {
            try (var executors = ZeroRuntimeExecutors.localPrototype("kcp-minimal-consumer", 1);
                    var runtime = RuntimeComposition.builder(RuntimeProfile.local())
                            .install(RuntimeBasics.module(new MapZeroConfig(Map.of()), executors))
                            .install(KcpRuntime.module("minimal", "127.0.0.1", 0, KcpProfile.BALANCED,
                                    (connection, frame) -> CompletableFuture.completedFuture(List.of())))
                            .build()) {
                runtime.start();
                return runtime.require(KcpRuntime.server("minimal")).boundPort();
            }
        }
    }
}
