# 性能方案 S0-S3 实施报告（2026-09-23）

本报告记录已确认的 S0-S3 方案在当前工作区的实施和验证。实施基于 2026-09-22 已有未提交优化，未执行 reset、清理或提交。S4（MPSC、跨异步缓冲借用、直连 RPC）没有启用。

## 环境与口径

- Windows 11 amd64，AMD Ryzen 9 7950X（16 核/32 线程），物理内存约 31.2 GiB。
- Oracle JDK 21.0.4（`D:\env\jdk21`）、Maven 3.9.8；JMH 1.37；TCP 进程显式使用 G1、`-Xms256m -Xmx512m`。
- JMH 结果保存在 [`target/performance-20260923`](../../target/performance-20260923)；短测的延迟是从计划发送到完整响应，直方图为 8192 个对数桶上界。吞吐是实际完成响应数，不包含失败、拒绝、超时或 pending。
- TCP 负载由独立 server/client JVM 驱动，payload 1 KiB，目标 2000/s，运行 30 秒；每档均使用最新的 `zero-benchmarks/target/benchmarks.jar`，并同时采样 JVM、RSS、private bytes 和 Windows handle。
- 两档 TCP 使用同一个 benchmark jar，SHA-256：`75DCD819C27FC9DFCA252815C3E4B4F777E0F4399232431F8233CD3E4DC96DE`。

## 已实施范围

| 区域 | 结果 |
| --- | --- |
| Ranking | 包内跨度跳表和 UID 索引，查询/Top/缺失 UID 走索引；写入同步维护索引，写密集场景成本保留在基准中。 |
| Actor | 每 Lane/全局有界准入、批次 drain、公平续调、关闭拒绝和拒绝恢复；Local 与 Executor 共享容量语义；类型解析使用注册快照缓存，内部消息 ID 使用进程前缀加计数。 |
| Protocol/Netty | 帧长度无复制读取、只读视图和缓冲 codec 入口；Netty ByteBuf 直接路径、同连接批写、写水位、连接/全局出站预算、NIO/AUTO/EPOLL 配置。 |
| AOI/Scene/FrameSync | AOI observe 差分快照和 observer 释放；Scene 内层状态绑定 Scene Lane；FrameSync 维护确定顺序并限制参与者历史。 |
| Cache/Runtime | Cache 统计计数改用 LongAdder；Actor runtime 负责调度器生命周期登记和关闭。 |

默认线格式保持不变。必要的 0.x API、错误码和默认预算变化见[迁移说明](../migrations/20260923-performance-plan.md)；变更摘要见 [`CHANGELOG.md`](../../CHANGELOG.md)。

## JMH 证据

以下是本轮保存样本中的代表值；不同基准的 fork、warmup、测量轮次以对应 JSON/日志为准，短样本用于比较方向，不作为生产容量承诺。

| 基准 | 优化后结果 | 观察 |
| --- | ---: | --- |
| Cache hit，8 线程 | 686.5M ops/s | 原实现约 72.4M ops/s；并发统计计数不再争用单一原子热点。 |
| Cache hit，32 线程 | 1.363B ops/s | 原实现约 56.3M ops/s；线程调度和桌面负载会放大短测波动。 |
| Ranking missing，100/10k/100k | 7.88/7.53/10.30 ns/op | 缺失 UID 不再扫描完整排序结构。 |
| Ranking rank，100/10k/100k | 65.80/188.21/307.68 ns/op | 查询随索引规模增长；写入维护跳表和 UID 表，不能用查询结果推断写入成本。 |
| Ranking update，100/10k/100k | 176.63/471.86/769.81 ns/op | 公开写入代价，避免只展示读路径收益。 |
| Game actorDefault | 119.00 ns/op | 默认内部消息 ID 路径显著低于旧 UUID 构造路径。 |
| Game AOI move+observe，100/2000/10000 | 2.21/51.58/286.11 µs/op | 差分 observe 减少重复事件和快照构建。 |
| Netty encode，1 KiB | 356.15 ns/op | 使用 ByteBuf 适配入口；64 KiB 以上仍受实际复制/分配边界影响。 |
| Netty decode，1 KiB | 231.01 ns/op | 入站从 ByteBuf 读取并在帧边界保留独立数据。 |

逐字节 UTF-8 实验在 ASCII/混合字符样本中吞吐退化，因此回退为 JDK UTF-8 实现；没有把该实验作为默认优化。完整原始样本包括 `cache-before/after-*`、`RankingBenchmark-after.json`、`ProtocolPathBenchmark-after.json`、`netty-baseline/final.json` 和 `game-baseline/after.json`。

## TCP 受控负载

| 连接数 | planned/offered/completed | failed/timeout/rejected/pending | p50 | p95 | p99 | p999 | server RSS 峰值 | server handles 峰值 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 60,000/60,000/60,000 | 0/0/0/0 | 63.999 µs | 120.319 µs | 196.607 µs | 20.316 ms | 216.4 MiB | 1,505 |
| 10,000 | 60,000/60,000/60,000 | 0/0/0/0 | 68.095 µs | 118.783 µs | 239.615 µs | 11.993 ms | 271.6 MiB | 10,795 |

两档实际完成吞吐分别为 2000.029/s 和 2000.028/s。客户端峰值 RSS 分别为 212.9 MiB 和 259.4 MiB；断连后 server/client 均正常退出，日志中没有 pending 泄漏。原始 JSONL 位于 `target/performance-20260923/tcp-1000-benchjar` 和 `tcp-10000-benchjar`，脚本为 [`RunTcpLoad.ps1`](../../scripts/performance/RunTcpLoad.ps1)。p999 的毫秒级尖峰来自 Windows 调度和连接建立尾部，不能当作稳定服务延迟保证。

## 功能与质量门禁

使用 JDK 21 执行 `mvn -Pquality,benchmarks verify -DskipITs`：56 个 reactor 模块构建成功，739 项测试通过，失败/错误/跳过均为 0；Checkstyle、PMD、SpotBugs（包括 JMH 生成 harness 排除规则）和 JaCoCo 通过。日志保存在 [`final-quality-3.log`](../../target/performance-20260923/final-quality-3.log)，SpotBugs 最终报告为 `BugInstance size is 0`。

此外执行：

- [`ZeroArchitectureGuard.java`](../../scripts/ZeroArchitectureGuard.java)：56 模块、20 条规则，0 违规、0 警告。
- [`VerifyPublicApiCompatibility.java --check`](../../scripts/VerifyPublicApiCompatibility.java)：`zero-core`、`zero-runtime`、`zero-protocol`、`zero-rpc-common`、`zero-data` 五个受保护模块通过；协议和数据模块只有允许的加法签名变化。
- Actor、Netty 慢消费者/出站预算、AOI、Scene、FrameSync、Cache、Ranking、协议缓冲和 runtime lifecycle 定向测试均包含在上述质量门禁中；TCP 另行覆盖断连、关闭和预算归零。

## 适用边界与未验证项

- 连接扫描受当前 Windows 单机端口、CPU、handle 和内存预算限制；没有据此推断 50,000 连接、Linux EPOLL、TLS 生产容量或跨机网络结果。
- 本轮没有运行 2 小时长稳，也没有完成跨机 RPC、外部数据库/消息中间件、JFR 独立采样和 8–24 小时发布级趋势验证。
- JMH 短测没有为所有路径设硬阈值；Ranking 写入、超大协议帧、密集 AOI 和低流量 flush 仍应按业务负载单独复测。
- TCP 预算是框架待写准入，不等于 JVM、Netty allocator、TLS 或 OS 总内存上限；持续过载应观察队列等待、拒绝计数和慢消费者，而不是无限提高预算。

## 复现

```powershell
$env:JAVA_HOME = 'D:\env\jdk21'
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
mvn -Pquality,benchmarks verify -DskipITs
& .\scripts\performance\RunTcpLoad.ps1 -RunName tcp-1000-benchjar -Connections 1000 -Rate 2000 -Seconds 30 -PayloadBytes 1024 -ServerJar zero-benchmarks/target/benchmarks.jar -ClientJar zero-benchmarks/target/benchmarks.jar
& .\scripts\performance\RunTcpLoad.ps1 -RunName tcp-10000-benchjar -Connections 10000 -Rate 2000 -Seconds 30 -PayloadBytes 1024 -ServerJar zero-benchmarks/target/benchmarks.jar -ClientJar zero-benchmarks/target/benchmarks.jar
```

计数更正（2026-09-23）：原稿的 1478 将每个测试类和模块汇总重复累加。本报告以 `final-quality-3.log` 的模块汇总去重为 739；后续增量结果独立报告，不与历史计数相加。
