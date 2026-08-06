# zeroServer 性能设计与基准

本文说明 zeroServer 的性能设计原则、当前可复现基准、结果解释和尚未覆盖的性能问题。所有数字都只描述指定机器、JDK、代码版本和工作负载，不代表生产容量、尾延迟、长稳或 SLA。

## 1. 性能设计原则

zeroServer 把协议、网络、Actor 调度、RPC pending、数据访问、缓存和场景循环视为关键路径。设计时优先关注：

- 减少临时对象、复制、装箱、反射和通用中间模型。
- 让玩家与场景状态绑定 Actor lane，避免业务共享锁。
- Netty IO 线程只做有界协议处理和投递，不执行阻塞业务或远程 IO。
- 跨 Actor 修改通过消息完成，避免共享可变状态。
- 远程 IO 使用显式异步接口、超时和受管执行器。
- 所有队列、pending、缓存、定时任务和连接入站路径都必须有容量边界。
- 生产安全语义不能只靠“免费”假设；日志字段、脱敏、指标标签和 observer 的成本也需要测量。

性能优化不能绕过协议兼容、线程安全、错误处理、审计或背压。任何优化都应先固定语义和 workload，再比较分配、延迟与吞吐。

## 2. 测试环境

本文结果于 2026-08-06 在以下环境产生：

| 项目 | 值 |
| --- | --- |
| CPU | AMD Ryzen 9 7950X，16 核 / 32 线程 |
| 内存 | 31.2 GiB 可见内存 |
| OS | Windows 11 专业版 10.0.22631，64-bit |
| JDK | Oracle Java 21.0.4 LTS，HotSpot 64-Bit Server VM |
| Maven | 3.9.8 |
| Protobuf | 4.35.0 |
| FlatBuffers | 25.2.10 |
| JMH | 1.37 |

测试机器不是隔离的性能实验室，Windows 后台任务、CPU boost、调度和温度都会影响结果。数字适合观察数量级和同轮相对差异，不适合直接推导线上 QPS。

## 3. 协议编解码横向比较

### 3.1 Workload

Zero Binary Protocol、Protobuf 和 FlatBuffers 使用同一逻辑 DTO：

- 5 个数值标量。
- 1 个必填字符串。
- 1 个 nullable 字符串，一半样本为 null。
- 64 字节 blob。
- 16 个 int、8 个 long、12 个 boolean。
- 6 个 item 子对象，每个包含 `int/int/long/double/string`。
- 6 个 key/value 属性对象。

共生成 1024 个不同样本。每轮 200,000 次操作，预热 4 轮、测量 5 轮；同一进程依次运行三种 codec。测量平均 payload 大小、编码、扫描全部字段的解码、只读取头部三个热字段和编码后完整解码往返。

这是一组手写轻量 micro benchmark，不是 JMH；它通过校验和和 volatile blackhole 降低死代码消除风险，但没有 JMH 的 fork 隔离、统计模型和 profiler 集成。

### 3.2 Java 21 结果

下面采用发布前最终复验结果；前两次完整复跑的排序一致，详见[协议编解码对比报告](reports/protocol-codec-comparison.zh-CN.md)。

| Codec | 平均体积 bytes | 编码 ns/op | 完整解码 ns/op | 热字段读取 ns/op | 往返 ns/op |
| --- | ---: | ---: | ---: | ---: | ---: |
| zero-proto | 437.46 | 353.47 | 296.71 | 12.18 | 669.83 |
| protobuf | 497.63 | 885.53 | 866.55 | 825.26 | 1905.95 |
| flatbuffers | 880.74 | 865.07 | 610.47 | 5.94 | 1495.04 |

以本轮 zero-proto 为 1.00×：

| Codec | 体积 | 编码耗时 | 完整解码耗时 | 热字段读取耗时 | 往返耗时 |
| --- | ---: | ---: | ---: | ---: | ---: |
| zero-proto | 1.00× | 1.00× | 1.00× | 1.00× | 1.00× |
| protobuf | 1.14× | 2.51× | 2.92× | 67.76× | 2.85× |
| flatbuffers | 2.01× | 2.45× | 2.06× | 0.49× | 2.23× |

### 3.3 如何理解

- 本 workload 中，zero-proto 的 payload 最小，编码、完整 DTO 解码和往返最快，适合作为框架内部高频 DTO 路径的当前默认选择。
- FlatBuffers 只读取三个头部字段时最快，体现了随机访问能力；但本 workload 的构建成本、完整扫描和体积更高。资源表、只读快照、共享 ByteBuffer 等场景应独立设计 workload，不能用本结果否定或证明。
- Protobuf 提供成熟生态、兼容治理和多语言工具链。本测试从独立业务 DTO 构造 Protobuf message；如果业务对象本身就是生成 message，编码成本会不同，但业务层也会绑定 Protobuf 模型。
- 三者的字段布局、兼容模型和使用方式不同。本表不是通用“谁永远更快”的排名。

### 3.4 复现

完整准备、代码生成、编译和运行步骤见[协议编解码对比报告](reports/protocol-codec-comparison.zh-CN.md)。核心执行参数为：

```powershell
java -cp $cp group.zn.zero.protocol.benchmark.CodecComparisonBenchmark 200000 4 5 1024
```

## 4. 可观测性 JMH

### 4.1 方法

`zero-benchmarks` 是默认 Reactor 之外、由 `-Pbenchmarks` 显式启用的 JMH 叶子模块。本轮参数：

```text
JMH 1.37
Mode: AverageTime
Threads: 1
Forks: 1
Warmup: 3 × 1s
Measurement: 5 × 1s
JVM: -Xms1g -Xmx1g -XX:+AlwaysPreTouch
Unit: ns/op
```

构建和运行：

```powershell
mvn -Pbenchmarks -pl :zero-benchmarks -am -DskipTests package
java -jar zero-benchmarks/target/benchmarks.jar `
  "group.zn.zero.benchmark.observability.*" `
  -wi 3 -i 5 -w 1s -r 1s -f 1 -t 1 `
  -jvmArgs "-Xms1g -Xmx1g -XX:+AlwaysPreTouch"
```

### 4.2 结果

| Benchmark | 参数 | 平均 ns/op | 99.9% CI 误差 |
| --- | --- | ---: | ---: |
| LogFields append | 0 fields | 262.395 | ±28.352 |
| LogFields append | 8 fields | 1836.568 | ±12.846 |
| LogFields append | 32 fields | 6827.603 | ±56.308 |
| MetricLabels record | 0 labels | 40.046 | ±3.141 |
| MetricLabels record | 4 labels | 219.377 | ±1.644 |
| MetricLabels record | 8 labels | 401.891 | ±10.859 |
| ProductionNetworkObserver | AUTH_SUCCEEDED | 2151.549 | ±55.015 |
| ProductionNetworkObserver | RATE_LIMITED | 2347.627 | ±137.947 |
| Redaction append | MISS | 338.248 | ±17.398 |
| Redaction append | HIT | 478.060 | ±34.611 |

这些 workload 测量的是当前真实对象构造、校验、内存注册和 Sink 路径，不是空循环。结果表明：

- 日志与指标字段数增加会带来明显且近似随字段数增长的成本，因此框架设置字段预算和低基数标签约束是必要的。
- 默认标识脱敏命中相对未命中增加了本轮约 140 ns/op；该差值只适用于固定脱敏策略和短字符串输入。
- 生产网络 observer 的两种场景都处于约 2.2–2.4 μs/op 数量级，但它们包含不同的真实日志/指标工作，不应互相计算“性能提升”百分比。

本轮没有启用 GC profiler，不能从表中推断 allocation rate；单 fork 也不足以建立严格回归阈值。若将来用于 CI 阈值，应增加 fork、独立 runner、环境噪声控制、GC/perf profiler 和历史分布。

## 5. 尚未完成的性能证据

以下方向已有实现或测试基础，但本次公开版本没有形成可以对外承诺的正式容量结果：

| 方向 | 需要测量 | 当前边界 |
| --- | --- | --- |
| Actor scheduler | 单 lane 顺序、跨 lane 并行、队列积压、公平性、分配 | 尚无隔离 JMH/压测结论 |
| Net frame | TCP frame encode/decode、粘包拆包、入站预算、连接并发 | 尚无真实网络 p99/容量结论 |
| RPC pending | 注册、完成、超时轮、容量满、Kafka 往返 | 尚无 broker 集群和故障注入容量结论 |
| Repository save | 映射、envelope、批量、CAS、失败保留 | 尚无真实数据库批量与长稳结论 |
| Cache get-or-load | hit/miss、single-flight、L2、穿透保护 | 尚无热点倾斜和网络分区结论 |
| Scene move loop | Actor 投递、移动、快照、AOI、广播 | 当前没有完整 AOI/广播容量模型 |

生产发布前应补充：真实业务 payload 分布、并发连接、持续压测、p50/p95/p99/p999、GC、内存、CPU、队列水位、故障恢复、外部中间件限流和至少数小时的长稳观察。

## 6. 提交性能结果的要求

贡献新的性能结论时，请同时提供：

- 精确提交、模块和 benchmark 源码。
- CPU、内存、OS、JDK/JVM、依赖版本。
- 数据模型、样本分布、并发、预热、测量、fork、单位和 profiler。
- 原始结果与摘要，不能只保留最好的一轮。
- 与基线的语义等价性说明。
- GC、分配、正确性校验和异常路径。
- 结果局限以及哪些线上结论不能由该测试推出。
