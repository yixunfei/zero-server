# protocol-codec 性能证据口径草案

```text
zero-performance-evidence=track|id=protocol-codec|status=readiness|benchmarkComplete=false|productionReady=false
```

本文定义 `protocol-codec` 首批性能证据的可复现口径草案。它服务于 zeroServer “高性能多场景处理”目标，但当前只作为后续 benchmark / JMH / 压测任务的输入，不是性能结果报告，也不是生产容量承诺。

## 1. 定位

`protocol-codec` 覆盖协议 DTO 编码、解码、热字段读取、完整 roundtrip 和 payload 大小。该路径位于 TCP frame、RPC envelope、generated dispatcher、玩家 / 场景 / 房间业务调用之前，首批证据先把协议编解码的 workload、环境、结果格式和风险边界固定下来。

本草案不做以下事情：

- 不新增 JMH 依赖、Maven profile 或 CI 门禁。
- 不运行 benchmark、压测、长稳、Docker 或 external-tests。
- 不定义性能阈值、SLA 或容量承诺。
- 不修改协议线格式、协议 ID、编解码规则、codegen 规则或运行时热路径。
- 不替代 [docs/reports/protocol-codec-comparison.zh-CN.md](../../../docs/reports/protocol-codec-comparison.zh-CN.md)，而是把它升级为后续可复现证据前的口径输入。

## 2. 当前证据

| 证据 | 状态 | 说明 |
| --- | --- | --- |
| [docs/reports/protocol-codec-comparison.zh-CN.md](../../../docs/reports/protocol-codec-comparison.zh-CN.md) | 已有 | 记录 Zero Binary Protocol、Protobuf、FlatBuffers 的手写 micro benchmark 对比，包含 Java 21 复验，非 JMH |
| [zero-protocol/src/benchmark/java/group/zn/zero/protocol/benchmark/CodecComparisonBenchmark.java](../../../zero-protocol/src/benchmark/java/group/zn/zero/protocol/benchmark/CodecComparisonBenchmark.java) | 已有 | 当前手写 benchmark 主体 |
| `zero-protocol/src/benchmark/proto/codec_comparison.proto` | 已有 | Protobuf 对照 schema |
| `zero-protocol/src/benchmark/fbs/codec_comparison.fbs` | 已有 | FlatBuffers 对照 schema |
| [zero-protocol/src/test/java/group/zn/zero/protocol/buffer/ZeroBufferBenchmark.java](../../../zero-protocol/src/test/java/group/zn/zero/protocol/buffer/ZeroBufferBenchmark.java) | 已有 | buffer 维度的局部性能参考 |

当前缺口：

- 已有 Java 21 单次复验；多环境重复采样与统计分布仍需建立。
- JDK、OS、CPU、JVM flags、预热、测量轮次和样本规模没有形成统一结果头。
- GC、allocation、p95 / p99、异常样本和多业务 payload 矩阵未固化。
- 现有结果不能作为 CI 阈值或生产容量承诺。

## 3. 环境元数据

后续任何正式性能证据至少应记录以下元数据：

| 字段 | 含义 | 示例 |
| --- | --- | --- |
| `evidenceId` | 本次证据编号 | `protocol-codec-20260710-local-win` |
| `track` | 性能 track | `protocol-codec` |
| `gitCommit` | 当前提交或工作树标识 | `HEAD` / `dirty:<short-note>` |
| `os` | 操作系统与版本 | `Windows 11` |
| `cpu` | CPU 型号、核心数、频率策略 | `16c/24t, performance mode` |
| `memory` | 内存容量 | `64GB` |
| `javaVersion` | Java 版本 | `21.x` |
| `jvmFlags` | JVM 参数 | `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` |
| `iterations` | 单轮迭代次数 | `200000` |
| `warmupRounds` | 预热轮数 | `4` |
| `measureRounds` | 测量轮数 | `5` |
| `sampleCount` | 样本数量 | `1024` |
| `toolType` | 工具类型 | `manual-micro-benchmark` / `jmh` |
| `thresholdMode` | 阈值模式 | `none` / `informational` / `blocking` |

当前阶段 `thresholdMode` 必须保持 `none`。任何 `informational` 或 `blocking` 都需要单独高风险确认。

## 4. Payload 矩阵

首批 payload 不应只测单一 DTO，应覆盖不同游戏服务器场景。建议先定义以下逻辑样本族：

| Payload | 场景 | 目标覆盖 | 主要字段 |
| --- | --- | --- | --- |
| `login-minimal` | 登录 / 选角 | 小请求、小响应、字符串和错误码 | `accountId`、`playerId`、`tokenDigest`、`errorCode` |
| `scene-move` | RPG 场景移动 | 高频小包、坐标、方向和版本号 | `playerId`、`sceneId`、`x`、`y`、`z`、`version` |
| `room-command` | 房间 / 对战命令 | 成员、命令、幂等序号 | `roomId`、`seat`、`commandType`、`commandSeq` |
| `frame-input` | 帧同步输入 | 固定帧号、输入数组、bitset | `roomId`、`frameNo`、`inputSeq`、`buttons`、`axis` |
| `npc-tick-event` | NPC tick | 批量实体、小状态枚举 | `zoneId`、`npcId`、`state`、`targetId` |
| `ranking-query` | 排行榜 / 赛季 | 分页、列表、长整型分数 | `seasonId`、`rankFrom`、`rankTo`、`scoreItems` |
| `world-shard-transfer` | 开放世界分片迁移 | 混合字段、状态交接 | `worldId`、`fromShard`、`toShard`、`entitySnapshot` |
| `state-snapshot-large` | 状态同步快照 | 大 payload、数组、嵌套对象、bytes | `sceneId`、`entities`、`attrs`、`blob` |

payload 矩阵只定义测量样本，不冻结业务协议 API。后续如果要把这些样本落成 `.si`、DTO 或 codegen 模板，必须单独确认。

## 5. 测量项

首批证据至少应覆盖：

| 指标 | 含义 | 备注 |
| --- | --- | --- |
| `avgPayloadBytes` | 平均 payload 字节数 | 用于观察协议体积 |
| `encodeNsPerOp` | 编码耗时 | 逻辑 DTO 到 `byte[]` |
| `fullDecodeNsPerOp` | 完整解码耗时 | 解码并读取全部业务字段 |
| `hotDecodeNsPerOp` | 热字段读取耗时 | 读取高频路由 / 实体字段 |
| `roundtripNsPerOp` | 编码后完整解码耗时 | 模拟请求处理前置路径 |
| `allocationBytesPerOp` | 每次操作分配 | 当前缺口，后续 JMH / profiler 补齐 |
| `gcCount` / `gcTimeMs` | GC 次数和耗时 | 当前缺口，后续统一采样 |
| `p95Ns` / `p99Ns` | 分位延迟 | 当前手写 benchmark 不提供，后续可由 JMH 或专用 runner 补齐 |
| `checksum` | 校验值 | 避免路径被错误优化或样本未读取 |

## 6. 结果格式草案

正式证据建议使用 Markdown 表格作为人工阅读入口，并保留一份机器可读摘要。当前阶段只定义字段，不要求新增文件格式。

Markdown 摘要建议：

```text
protocol-codec-performance=ok|payloads=8|java=21|thresholdMode=none|requiresConfirmation=true
```

机器可读摘要字段建议：

| 字段 | 含义 |
| --- | --- |
| `track` | 固定为 `protocol-codec` |
| `payload` | payload 样本名 |
| `codec` | `zero-proto` / `protobuf` / `flatbuffers` / 后续候选 |
| `avgPayloadBytes` | 平均字节数 |
| `encodeNsPerOp` | 编码耗时 |
| `fullDecodeNsPerOp` | 完整解码耗时 |
| `hotDecodeNsPerOp` | 热字段读取耗时 |
| `roundtripNsPerOp` | 往返耗时 |
| `allocationBytesPerOp` | 分配，当前可为空 |
| `gcCount` | GC 次数，当前可为空 |
| `checksum` | 校验值 |
| `notes` | 非结构化备注 |

## 7. 复现命令草案

当前已有手写 benchmark 可作为历史参考：

```powershell
$root='target\codec-comparison'
$cp="$root\classes;$root\deps\protobuf-java-4.35.0.jar;$root\deps\flatbuffers-java-25.2.10.jar"
java -cp $cp group.zn.zero.protocol.benchmark.CodecComparisonBenchmark 200000 4 5 1024
```

后续如果进入正式 benchmark，可以优先选择两步：

1. 先用现有手写 benchmark 在 Java 21 下复跑，补齐环境元数据和结果字段。
2. 再在用户确认后复用已有 opt-in JMH profile，把稳定 workload 迁入 JMH。

## 8. 高风险确认边界

以下动作必须单独建档并等待用户确认：

- 新增 JMH 依赖、Maven profile、benchmark 模块或 CI job。
- 将任何性能数字设置为阈值、发布阻断或生产容量承诺。
- 根据结果修改协议线格式、协议 ID、字段布局、nullable 策略或集合编码。
- 根据结果修改 codegen 规则、DTO 结构或 reader/writer 生成方式。
- 引入 Protobuf、FlatBuffers 或其他 codec 作为正式 adapter 或可切换协议。
- 将 benchmark 扩展到网络、RPC、缓存、存储或场景 actor 热路径。

## 9. 推荐下一步

先按[现有基准方法](../performance.zh-CN.md)复跑，再从本页 payload 矩阵选取新增负载，复用已有 `zero-benchmarks` JMH 模块。报告记录环境、代码版本、命令、分配和延迟；正式阈值和协议优化作为独立变更评审。
