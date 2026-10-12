# zero-benchmarks 示例

## 依赖选择

该模块只在性能验证 profile 中启用，不作为业务运行时依赖。

仓库根 POM 已声明 benchmarks profile，使用下方 -Pbenchmarks 命令启用，无需自行添加空 profile。

## 接入入口

JMH 性能证据。

核心 API：`CacheReadBenchmark（JMH @Benchmark）`。

JMH 性能用法入口；需要显式启用 benchmarks profile。

从 [CacheReadBenchmark.java](../src/main/java/group/zn/zero/benchmark/performance/CacheReadBenchmark.java) 的 **JMH @Benchmark 方法** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -Pbenchmarks -pl zero-benchmarks -am -DskipTests package
java -jar zero-benchmarks/target/benchmarks.jar CacheReadBenchmark -wi 1 -i 1 -f 1
```

benchmarks 不在默认 Reactor；此短运行仅验证用法，不可当作稳定性能结论。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../../examples/README.md)、[模块图](../../docs/module-map.md)、[接入卡片](../../examples/module-access/zero-benchmarks.md)。
