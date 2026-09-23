# 性能反馈核验与本地优化（2026-09-22）

本报告比较原始提交 `62351882917f917996db4c5c1d7b187f83f4d110` 与本次优化的相同工作负载。优化后的源码摘要及逐轮样本见 [JSON 证据](performance-feedback-20260922.samples.json)。这是手工微基准，不是 JMH、真实全服压测或生产容量证明。

## 反馈核验与实现

| 反馈 | 核验与处理 |
| --- | --- |
| Actor 全局锁热点 | 全局竞争结构成立；改为 64 个段内短锁，注册表写时复制且保持精确/注册顺序语义。drain 捕获本次队列身份，避免每条消息重复定位队列；空闲回收和拒绝清理与入队在同段内原子执行。单热点 lane 仍串行。 |
| AOI 全量扫描 | 成立；二维均匀网格筛选候选，使用 long 边界和 floorDiv 处理极端/负坐标。极大范围遍历已占用桶；独占构建结果后包装成不可变快照，避免再次建表。密集输出仍为 O(K)，observe 还包含事件排序和观察者快照维护。 |
| 缓冲频繁分配 | 默认 codec 每次新建 writer/堆数组再复制输出；现在每个平台线程有界复用一个不超过 64 KiB 的 writer，嵌套借用隔离、异常归还、大包不保留、虚拟线程旁路。公开返回 byte[] 的必要分配和 Netty 边界复制仍存在。 |
| FFM 已在 Java 21 正式引入且零成本 | 不准确。[JEP 442](https://openjdk.org/jeps/442) 是 Java 21 第三次预览，[JEP 454](https://openjdk.org/jeps/454) 在 Java 22 正式定版。保留 Java 21 非预览基线，在 ZeroUnsafe/NativeMemoryZeroBuffer 注释未来迁移要求；以可达性栅栏保护现有 native 操作，初始化失败释放内存。 |

补充修复了旧注销句柄误删同对象新注册的问题，以及 direct executor 在完成检查/回调注册竞态中递归续调的问题。后者通过 20,000 条排队消息和确定性完成竞态测试验证。

## 环境与测量方法

- Windows 11，AMD Ryzen 9 7950X（16 核/32 逻辑处理器），Oracle JDK 21.0.4，Maven 3.9.8。
- JVM：`-Xms512m -Xmx512m`，默认 GC；测量进程未加载 JaCoCo/JFR agent，期间不并行运行构建或测试。
- 每个实现 3 个独立 JVM，每项 3 轮预热、5 轮测量；表格为 15 个测量轮的中位数。原实现与优化实现交替运行，保留每轮值，不设性能通过阈值。
- Actor：8 或 32 个生产者，4 个工作线程，1 或 16,384 个 lane；每生产者每轮 20,000 条消息。计时包含提交到所有 completion 完成，校验处理总数；提交任务的准备在计时之外。不采集跨线程分配，JSON 的 `-1` 表示未测量。
- AOI：100/10,000 个实体；稀疏位置间隔 128、查询范围 32、每轮 5,000 次查询，固定返回 1 个实体；密集场景 10,000 个实体同点，每轮 500 次查询且返回全部实体。每次校验结果数量并消费返回集合。
- Codec：64 B、1 KiB、32 KiB、128 KiB bytes payload，预先校验 round trip，计时内物化独立 byte[] 并消费引用、长度和尾字节。每轮迭代 `max(2000, 32000000 / payloadSize)` 次。
- B/op 使用 `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` 的单线程差值，反映 Java 堆分配；不包含 native 内存或网格长期保留量，不能等同于 GC 暂停时间。

## 测量结果

| Workload | 原实现 ns/op | 优化后 ns/op | 吞吐比（后/前） | 原实现 B/op | 优化后 B/op |
| --- | ---: | ---: | ---: | ---: | ---: |
| `actor-producers-32-lanes-1` | 120.33 | 123.11 | 0.98x | 未采集 | 未采集 |
| `actor-producers-32-lanes-16384` | 199.84 | 168.81 | 1.18x | 未采集 | 未采集 |
| `actor-producers-8-lanes-1` | 110.60 | 102.29 | 1.08x | 未采集 | 未采集 |
| `actor-producers-8-lanes-16384` | 173.69 | 156.09 | 1.11x | 未采集 | 未采集 |
| `aoi-100-sparse` | 709.04 | 193.50 | 3.66x | 624.00 | 343.52 |
| `aoi-10000-dense` | 390,293.20 | 143,455.00 | 2.72x | 571,704.00 | 451,320.00 |
| `aoi-10000-sparse` | 49,952.76 | 620.74 | 80.47x | 560.00 | 425.57 |
| `frame-1024` | 183.96 | 164.89 | 1.12x | 3,176.00 | 2,112.00 |
| `frame-131072` | 22,640.20 | 22,872.35 | 0.99x | 393,320.00 | 393,360.00 |
| `frame-32768` | 5,905.40 | 4,576.50 | 1.29x | 98,408.00 | 65,600.00 |
| `frame-64` | 38.18 | 38.47 | 0.99x | 336.00 | 192.00 |
| `generated-1024` | 121.51 | 85.02 | 1.43x | 2,096.00 | 1,048.00 |
| `generated-131072` | 13,858.90 | 14,394.70 | 0.96x | 262,192.00 | 262,232.00 |
| `generated-32768` | 3,769.70 | 2,512.50 | 1.50x | 65,584.00 | 32,792.00 |
| `generated-64` | 18.03 | 17.25 | 1.05x | 176.00 | 88.00 |

多 lane 的 32 生产者样本吞吐中位数提高约 18%；单热点 lane 的同组结果约低 2%，两者采样范围重叠，不能归纳为所有负载都提速。稀疏 AOI 的大幅提升来自空间筛选；密集场景改进来自减少集合构建，不改变 O(K) 输出下界。

1 KiB/32 KiB generated payload 的堆分配约减半，frame 仍包含防御性 payload 副本，因此降幅较小。64 B frame 吞吐基本持平；128 KiB 包不进入缓存，生成式编码本轮约慢 4%、多分配 40 B/op，体现大包旁路和包装成本。没有据此设置回归阈值。

## 复现

在仓库根目录使用 Java 21。Windows PowerShell 示例：

```powershell
$env:JAVA_HOME = 'D:\env\jdk21' # 替换为本机 JDK 21
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
mvn -B -pl zero-actor,zero-aoi,zero-protocol -am test
$benchClasspath = 'zero-core/target/classes;zero-actor/target/classes;zero-aoi/target/classes;zero-protocol/target/classes'
javac -cp $benchClasspath -d target/performance-feedback-harness scripts/performance/PerformanceFeedbackBenchmark.java
java -Xms512m -Xmx512m -cp "target/performance-feedback-harness;$benchClasspath" PerformanceFeedbackBenchmark
java -Xms512m -Xmx512m '-Dbench.producers=32' -cp "target/performance-feedback-harness;$benchClasspath" PerformanceFeedbackBenchmark actor
```

每条 java 命令运行 3 次并分别保存输出；类路径分隔符在 Linux/macOS 上改为 `:`。对照原实现时在独立检出中构建上述原始提交，然后用完全相同的基准源码和 JDK 参数运行。基准只调用原实现已存在的 API。本次原始 classes 在任何运行时源码修改前复制到本地 `target/performance-feedback-baseline/`，避免对照依赖当前改动或 Maven 缓存版本。

基准没有新增 Maven profile、运行时依赖或修改冻结的 `zero-benchmarks` 依赖集合。`scripts/performance/PerformanceFeedbackBenchmark.java` 独立编译，默认测试与构建不会自动运行。

## 功能与质量验证

使用 Java 21 执行：

| 命令 / 检查 | 结果 |
| --- | --- |
| `mvn -B -Pquality verify -DskipITs` | 全仓 57 个 reactor 项成功；710 项单元测试，失败/错误/跳过为 0；Checkstyle、PMD、SpotBugs、JaCoCo 通过 |
| `mvn -B -pl zero-actor -am -Pquality verify -DskipITs` | 最后补充注册并发与哈希碰撞测试后执行；33 项 Actor/Core 测试及质量检查通过，其中多数与全仓验证重叠 |
| `mvn -B -pl zero-server-starter,zero-net,zero-state-sync -am -Pintegration-tests verify` | 579 项测试通过，包括 1 项 Stage3GeneratedBoFullLoopIT；其余为重叠单元测试 |
| `java scripts/ZeroArchitectureGuard.java` | 56 模块、20 条规则，0 违规和警告 |
| `java scripts/VerifyPublicApiCompatibility.java --check` | 5 个受保护模块通过；zero-protocol 的 287 项签名不变 |

现有 API 基线工具不覆盖 zero-aoi；新增 cellSize 构造器记录于迁移说明，不改动无关基线文件。原始日志位于本地 `target/performance-feedback-full-quality.log`、`target/performance-feedback-actor-final-quality.log`、`target/performance-feedback-integration.log`、`target/performance-feedback-architecture.log`、`target/performance-feedback-api.log`。本轮不连接外部数据库或消息中间件。

核心回归包括多生产者同 lane 不重叠及 FIFO、空闲回收/重新投递、注册快照并发发布、哈希碰撞 lane 隔离、异步和拒绝恢复；4 种 cellSize 下随机增删改与全量参考模型对照，极端 int 范围、跨格与事件顺序；编码嵌套、异常、并发、黄金字节、独立结果、内存保留上限和虚拟线程旁路；native 扩容/双向重叠复制/幂等关闭、direct 偏移/只读/游标边界。

## 解释与限制

- 全局锁拆分的收益取决于 lane 分布、执行器排队和 handler 成本；单 lane 不获得并行收益。测试未测每次 dispatch 的 p95/p99、锁等待时间、长期队列积压或公平性。
- 稀疏局部查询的提升来源于避免访问远处实体；密集查询只能减少集合和遍历成本，仍需输出 K 个实体。此微基准未计入网格构建、跨格移动的维护成本、observer 排序、广播、真实地图热点和额外索引常驻内存。
- writer 缓存总预算随存活的平台编码线程数增长；小包的 ThreadLocal 成本可能抵消吞吐收益，超大包仍分配且可能增加少量包装成本。返回数组分配无法由内部复用消除。
- direct/native 改动有功能验证，但本轮未量化其单独性能；可达性栅栏遵循 JDK 的资源生命周期约束，测试不能证明所有 Cleaner 时序。FFM 和并发 native close 不在本次实现范围。
- 轮次较短，JIT、CPU 调频和桌面后台负载可能改变结果。保留原始样本用于观察波动，不使用这些数字设发布阈值或外推真实在线人数。

接入约定和回退方式见 [0.x 迁移说明](../migrations/20260922-performance-feedback.md)。
