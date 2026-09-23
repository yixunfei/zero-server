package group.zn.zero.benchmark.performance;

import group.zn.zero.cache.InMemoryCacheService;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** 多线程共享缓存读路径，使用 -t 1/8/32 比较统计竞争。 @author zn */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class CacheReadBenchmark {
    /** 共享线程安全缓存。 */
    private InMemoryCacheService<String, String> cache;
    /** 初始化热 key；测量期间只读。 */
    @Setup public void setup() {
        cache = new InMemoryCacheService<>();
        cache.put("key", "value").toCompletableFuture().join();
    }
    /** @return 完成的命中结果；线程安全，更新命中计数。 */
    @Benchmark public Object hit() { return cache.get("key").toCompletableFuture().join(); }
    /** @return 完成的未命中结果；线程安全，更新未命中计数。 */
    @Benchmark public Object miss() { return cache.get("missing").toCompletableFuture().join(); }
}
