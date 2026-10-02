# 游戏服务器开发与验收标准

更新日期：2026-10-01。适用版本：`0.1.2-SNAPSHOT`。当前状态：`planned`。

本文规定后续功能如何验证，避免把源码存在、单元测试通过、外部 Adapter 可连接和生产可用混为一谈。它适用于 Player、Scene、Room、AOI、状态同步、帧同步、NPC、Ranking、World、RPC、数据和缓存功能。

## 1. 验证层级

| 层级 | 目的 | 典型环境 | 能证明什么 |
| --- | --- | --- | --- |
| L0 静态与架构 | 检查依赖、API、文档和配置 | 无外部服务 | 结构和契约没有明显违规 |
| L1 focused test | 验证单模块行为 | JDK 21、本地实现 | 局部状态机、顺序、错误和并发边界 |
| L2 本地纵切片 | 验证业务链路 | 单 JVM、Local Starter | 协议到业务到状态到响应可运行 |
| L3 外部 Adapter | 验证真实驱动 | 隔离 Kafka/DB/Redis/Nacos | 连接、读写、健康和基础恢复 |
| L4 双 JVM 故障 | 验证进程边界 | 两个独立 JVM + 外部依赖 | owner、路由、epoch、重连和接管 |
| L5 容量与长稳 | 验证负载边界 | 固定硬件和可重复负载 | p99、队列、内存、GC、拒绝和趋势 |
| L6 发布与恢复 | 验证运营闭环 | 隔离部署环境 | 备份、升级、回滚、审计和 RPO/RTO |

L0-L2 通过不能推出 L3-L6 通过。L3 单实例 Adapter 通过不能推出集群、跨机房或生产容量通过。

## 2. 通用证据格式

每次验证至少保存：

- 代码版本和工作区状态。
- JDK、Maven、操作系统和容器版本。
- 外部组件版本、配置来源和认证方式。
- 测试命令和参数。
- 输入规模、并发数、持续时间和预热方式。
- 成功、失败、拒绝、超时和重试数量。
- p50/p95/p99、吞吐、队列、in-flight、内存和 GC。
- 日志、指标、审计和故障注入结果。
- 清理步骤。
- 未验证项和剩余风险。

单独的一行“test passed”不能作为生产证据。

## 3. 按游戏类型的最低验证集合

### 3.1 RPG 登录和场景同步

必须覆盖：

- 登录成功、重复登录和认证失败。
- 玩家数据加载、版本冲突和落库失败。
- 场景进入、移动、离开和重复请求。
- AOI 加入、离开和观察者释放。
- Snapshot / Delta 生成和重新同步。
- 慢客户端、出站预算和连接关闭。
- 断线、重连和旧 session generation。

核心指标：登录 p99、场景进入 p99、Actor queue、AOI candidate 数、出站 bytes、丢弃/合并帧、重连耗时。

### 3.2 Room 和匹配游戏

必须覆盖：

- 创建、加入、ready、start、tick、settle、close。
- 重复加入、重复 ready、重复 settle。
- Room owner 退出和新 owner 接管。
- 旧 epoch 写入拒绝。
- checkpoint 恢复。
- 断线重连和重连窗口过期。
- 结算未知结果、对账和补偿入口。

核心指标：房间创建率、命令拒绝率、Tick 延迟、owner 迁移耗时、checkpoint latency、重连成功率、结算重复率。

### 3.3 帧同步

必须覆盖：

- 重复输入和乱序输入。
- 迟到输入、缺失输入和 Tick 超时。
- Snapshot digest 不一致。
- 客户端重连和历史帧恢复。
- 跨语言固定输入向量。
- 输入伪造和会话过期。

核心指标：帧推进延迟、迟到输入比例、缺失输入比例、hash mismatch、回放恢复耗时、历史输入内存。

### 3.4 NPC 和动态世界

必须覆盖：

- NPC spawn、tick、pause、despawn。
- 单个行为失败不影响同区域其它 NPC。
- Tick budget 超限和降频。
- 区域迁移、owner fencing 和旧快照拒绝。
- 寻路或远程行为执行超时。

核心指标：区域 tick 延迟、NPC backlog、预算拒绝、行为失败率、迁移耗时、活跃/休眠比例。

### 3.5 Ranking 和赛季

必须覆盖：

- `SET`、`MAX`、`ADD` 的并发合并。
- 稳定排序和 TopN 上限。
- 赛季冻结、结算、归档。
- 重复结算和奖励未知结果。
- Redis/数据库重启后的恢复。

核心指标：写入吞吐、热点 key、榜单查询 p99、结算耗时、重复结算拒绝、恢复对账数量。

## 4. 通用故障注入

所有需要外部系统的功能，至少设计以下故障：

- 连接建立失败。
- 连接中断。
- 请求超时。
- 响应迟到。
- 重复响应。
- 消费重平衡。
- 数据库重启。
- 连接池耗尽。
- 队列达到上限。
- 进程在写入确认前退出。
- owner 在迁移中退出。
- 新旧版本同时运行。

每个故障都必须有预期状态、ErrorCode、指标、日志和清理结果。不能只验证“程序没有崩溃”。

## 5. 容量测试规范

容量测试必须先定义：

- 目标业务场景。
- 连接数或房间数。
- 每秒请求、Tick 或输入数。
- Payload 大小。
- 目标 p99。
- 最大允许拒绝率。
- 最大内存和 GC 预算。
- 运行时间。

建议输出：

```text
scenario=room-owner-failover
commit=<git revision>
jdk=21
duration=1800s
rooms=1000
players=10000
commandsPerSecond=5000
p95=...
p99=...
queueMax=...
inFlightMax=...
heapMax=...
gcPauseP99=...
rejected=...
errors=...
productionReady=false
```

不得从单一机器、单一并发度或短时运行结果推导通用容量。不同游戏类型必须分别测量。

## 6. 线程和 Actor 验证

每个 Actor/异步功能至少验证：

- 同 lane 不并行。
- 不同 lane 可以并行或按策略限流。
- handler 未完成时预算仍被占用。
- handler 成功、异常、取消、超时都会释放预算。
- executor 拒绝不会遗留队列。
- completion 回调不会跨线程直接修改状态。
- 关闭会停止新提交并清理已登记资源。
- 外部 IO 不在 Actor lane 或 Netty IO 线程同步阻塞。

## 7. 观测验收

一条可验收的业务链路至少要能关联：

```text
request traceId
  -> protocol event
  -> actor message
  -> remote RPC / data call
  -> state version / owner epoch
  -> audit
  -> response
```

要求：

- ErrorCode 在错误日志和对外结果中一致。
- traceId 贯通入口、RPC、数据、MQ、审计和指标。
- 标签不包含 playerId、roomId、地址等高基数值。
- 敏感配置和凭据不会进入日志、诊断或测试输出。
- 队列、in-flight、拒绝和恢复都可查询。

## 8. 发布就绪门禁

只有满足以下条件，才可以针对具体项目评估生产就绪：

1. 本地完整验收通过。
2. 真实 Adapter 连接、健康和恢复通过。
3. 关键业务有双 JVM owner/迁移/重连证据。
4. 目标负载完成容量和长稳测试。
5. 安全入口、TLS、限流和认证已接线。
6. GM 权限、审批和审计已持久化并验证。
7. 日志、指标、告警和 trace 形成闭环。
8. 备份、恢复、升级和回滚完成演练。
9. 协议、配置、Provider ID 和数据 schema 有兼容门禁。
10. 未覆盖边界已明确记录，且不会被 README 或发布说明隐藏。

zeroServer 框架自身继续保留 `productionReady=false`，直到通用能力和公开证据达到项目要求。具体游戏项目可以在自己的部署材料中声明已验证范围，但不能把单个示例结果外推到整个框架。

## 9. 文档和代码同步要求

功能改动至少同步以下位置中的适用部分：

- `docs/capability-matrix.zh-CN.md`：状态和缺口。
- `docs/module-map.md`：模块、入口和依赖变化。
- 对应组件契约：Room、AOI、Frame Sync、NPC、Ranking、World。
- `docs/operations/performance.zh-CN.md`：负载和证据方法。
- `docs/migrations/`：0.x 行为、API、协议或配置变化。
- `CHANGELOG.md`：用户可见变化。

历史报告只能说明指定日期和代码范围，不能替代当前工作区重新验证。
