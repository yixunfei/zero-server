# 性能增量实施报告（2026-09-23）

本批延续已确认方案，交付 EventBus 注册快照/同步完成路径、AOI 内部观察遍历、Frame 只读 reader 与 Java 生成分发、精确 TopN 复用，以及 direct/Netty 批量重叠搬移。业务 BO、调度完成含义、线格式、默认配置和依赖方向保持。必要的新增读取入口见[迁移说明](../migrations/20260923-performance-incremental.md)。

排行榜拆锁未实施：原服务允许同步跨榜回调重入，逐榜锁和锁外阻塞通知均有形成等待环路的风险。本批保留原通知/异常契约，采用计划允许的局部优化。Actor 延迟包装、UTF-8 CharsetEncoder 批写及生成 DTO 尺寸规划没有足够净收益，实验后均未进入默认路径。S4 无启用证据。

## 环境与可复现性

- 分支 `codex/performance-incremental-r0-r3`；基线是进入本批时的工作区（包含此前未提交 S0-S3），不是单独 HEAD。
- Windows 11、Ryzen 9 7950X、32 逻辑核、约 32 GiB RAM；开始时可用约 19 GiB。Oracle JDK 21.0.4，Maven 3.9.8，G1。
- JMH 1.37，每个组合 2 forks、2×500ms warmup、3×500ms measurement，`-Xms512m -Xmx512m -XX:+UseG1GC`，GC profiler。构建与所有基准串行，未启用外部服务。
- 基线源码/POM 1115 个文件的 SHA-256、压缩源码及实际 jar 位于 `target/performance-incremental-20260923/baseline-manifest.json`、`baseline-source.zip`、`baseline.jar`。本批各阶段 jar 均保留，不用后来的源码冒充旧测量输入。
- 原始 JMH 样本、参数、误差区间及验证摘要收录于[数据文件](performance-incremental-20260923.samples.json)。本地日志及中间产物在上述 target 目录。

复现脚本：`scripts/performance/RunIncrementalBenchmarks.ps1`、`RunFinalIncrementalScans.ps1`、`RunGeneratedDtoExperiment.ps1`。脚本中的 baseline/current/final 指各阶段固定 jar；从干净源码重跑时需先保存相应阶段制品。生成 DTO 实验用现有 codegen CLI 生成 fixture 到 target，再单独 javac 编译 JMH，不增加默认业务构建依赖，不手工修改生成文件。

## 主要结果

以下为平均完整操作时间，单位 ns/op。AOI 一次操作包含该场景全部观察者的一轮更新/观察；Ranking/Actor/EventBus 是同步完成或等待异步完成后的成本。不能把这些值或倒数当作目标速率请求的 P99/P99.9 或容量上限。

| 工作负载 | 基线 ns/op | 本批 ns/op | 基线 → 本批 B/op |
| --- | ---: | ---: | ---: |
| EventBus，1 线程，8 个同步 handler | 67.7 | 12.7 | 744 → 24 |
| EventBus，8 线程共享，8 个同步 handler | 551.3 | 13.8 | 744 → 24 |
| EventBus，1 线程，8 个真实异步 handler | 10736.3 | 9112.7 | 2776 → 1928 |
| AOI，2000 实体，16 观察者，1% 更新 | 1139985.1 | 562516.7 | 1572512 → 19172 |
| AOI，2000 实体，16 观察者，100% 更新 | 2434833.2 | 1813910.6 | 3550970 → 2004736 |
| Ranking，8 线程共享，单榜纯读 Top10 | 708.7 | 354.3 | 240 → 24 |
| Ranking，8 线程共享，8 榜，10% 写 | 1073.8 | 560.6 | 236 → 44 |
| direct，32KiB bytes，8 层对象回填 | 50481.1 | 2371.4 | 约 0 → 约 0 |
| Netty direct，32KiB bytes，8 层对象回填 | 220531.9 | 2275.6 | 约 0 → 约 0 |

AOI 静态查询利用场景序号、中心和范围不变且观察状态引用稳定时直接返回，2000 实体/16 观察者每轮约 90 ns、近零分配；这只代表没有任何登记更新的场景，不代表移动场景也具有相同成本。注册更新或查询参数变化会重新遍历。

direct 使用 JDK 文档保证的绝对批量 put 处理双向重叠；Netty 只在单段内存使用该路径，组合内存回退。复用的 NIO 视图在扩容后失效，随机双向重叠、slice 边界及扩容回归均验证。表中为复用 writer 的稳态负载，不等于每个网络请求完全零分配。

## 代价与负样本

- EventBus 将快照复制移到注册：8 线程、8 个已有 handler 的注册/注销由 422 ns、72 B 增至 1215 ns、728 B。适合注册低频、发布高频的既有使用方式，不声称注册成本降低。失败派发保持死信和聚合，8 个失败 handler 为 12.2μs → 11.5μs。
- AOI 为每个观察到的实体增加代次记录。10 场景×2000 实体×16 观察者的显式 GC 探针，观察后的 heap 为 23.6 MB → 31.4 MB；释放观察者后为 10.66 MB → 10.77 MB。工作区每次清空引用，只复用不超过 4096 项查询的候选数组，观察者表在 forget 后移除。该短探针解释额外保留量和释放方向，不替代 2 小时长稳。频繁 observer 销毁/重建仍改善：2000 实体单次由 185.7μs/409.6KB 降至 148.7μs/315.7KB。
- Ranking 纯写没有收益：单榜约 3.61μs → 3.68μs，多榜约 3.69μs → 3.74μs，差异与短样本波动重叠。每榜只保留一个至多 100 条目的不可变 Top 列表；提交前后版本一致，通知前失效。**全局锁及慢通知阻塞仍存在。**
- Actor 延迟包装实验的拒绝负载均约 1640 B/op，未见明确分配改善。真实 4 消费线程、8 生产线程及异步完成样本保留，但不把波动中的时间变化认作收益；已恢复进入本批时的调度器实现。
- UTF-8 缓存 CharsetEncoder 虽把 heap 混合 1Ki 字符的分配从 4784 B 降至 56 B，完整编码时间却从约 1036 ns 增至 3266 ns；ASCII 与非法 surrogate 也回退，direct/Netty 同方向。保留 JDK 实现。实验含长度遍历、nullable VarInt、容量检查和编码；扩容正确性在 setup 检查，表中稳态不包含每次冷启动扩容。
- 真实生成 DTO 的长度规划只遍历每个子对象一次，使用当前调用独占的有界长度数组；包含 UTF-8 长度扫描、List<Integer> 和 nullable 递归对象。8 层、每层约 1Ki 字符，heap 从 10.4μs 增至 16.3μs，direct 从 10.5μs 增至 16.0μs，分配不变。黄金字节一致，默认保留回填，无新增 sizing SPI 或长期 DTO 尺寸缓存。

## 网络所有权与真实 DTO

保留 ProtocolFrame record 和公开数组构造器的防御复制。入站 Netty → 临时 payload 数组 → Frame 自持有数组仍有两次用户态复制；新增 Frame reader 消除业务读取时的整包副本。字符串、集合和 byte[] 字段仍按 DTO 的正常语义物化，不把 TCP/TLS 到对象描述为零复制。

仅使用 `payloadView` → `ZeroReader(ByteBuffer)` 时，小型生成 DTO 的读取出现约 25%–35% 回退，因此最终生成分发器使用 `frame.payloadReader()`。它直接读取私有数组并仅对固定 JDK UTF-8 解码器开放内部读取；自定义 Charset 使用副本。借用切片的 `array()`、可写 ByteBuffer 或自定义 decoder 均不能取得可写内部存储。泛型 `ProtocolCodec.decodeView` 保留正确的 ByteBuffer 输入能力及自定义 codec 数组回退。

最终 Frame reader 的完整生成 DTO 对照记录于数据文件的 `dto-frame-final`。内容覆盖混合字符串、整数集合、nullable 子对象、1/8 层嵌套与 16/1024 字符规模：

| 生成 DTO | 原数组读取 ns/op → 最终 Frame reader | B/op |
| --- | ---: | ---: |
| 1 层、约 16 字符 | 131.5 → 129.9 | 688 → 616 |
| 1 层、约 1024 字符 | 1729.8 → 1562.1 | 9424 → 7672 |
| 8 层、每层约 16 字符 | 1009.9 → 1004.2 | 5096 → 4592 |
| 8 层、每层约 1024 字符 | 10841.8 → 9761.8 | 74992 → 61040 |

小 DTO 时间持平，分配均降低；长字符串时间仍有采样波动，不据此承诺固定延迟下降比例。最终 jar 的 EventBus/AOI/Ranking/批量搬移字节码与各自测量 jar 相同，Actor 字节码与基线相同；逐类 SHA-256 保存在数据文件中。

Java 完整生成协议 → TCP → 业务 BO/session → 响应链路使用新入口验证；已有数组入口和自定义 codec 继续可用。异步持有使用 Frame 自持有数据，不跨异步借用 ByteBuf，不改变 DTO 的编码时机、出站预算、flush 完成或线程模型。

## 验证与边界

完整 `mvn '-Pquality,benchmarks' verify -DskipITs` 的 56 个 reactor 模块通过；补充及最终只读 reader 变更再执行受影响模块质量检查。Checkstyle、PMD、SpotBugs、JaCoCo 通过。最终 Surefire XML 去重为 **758 项测试，失败/错误/跳过均为 0**；完整 gate 首次 754 项，其后补齐 4 项有针对性的回归并通过相关模块门禁。不把测试类输出和模块汇总相加。

架构守卫：56 模块、20 规则、0 违规/警告。公共 API 基线：5 模块通过，新增读取方法为 additive。覆盖 EventBus 优先级/注销身份/单次快照/异步完成竞态/失败死信，AOI 单/多观察者随机参考模型/异常重试/生命周期/极端坐标，Ranking 索引参考模型/通知异常/跨榜重入，协议只读性/自定义 Charset/畸形输入/黄金字节/重叠搬移和网络生命周期。

历史 S0-S3 报告的 1478 项是重复计数，实际模块汇总为 739；该报告及任务 VERIFY 已更正。本批不将历史数量再次计入新增成果。

未验证或未启用：2 小时及 8–24 小时长稳、跨机 RPC/外部中间件、Linux EPOLL、独立生产 TLS 容量、50k 连接、完整目标速率生成 DTO 网络容量与 JFR 硬件归因。本批执行 Java 网络/生成链路和多语言生成产物测试，未启动独立 TS/.NET 客户端运行时。没有重新发布，也没有据短测改变容量默认值。
