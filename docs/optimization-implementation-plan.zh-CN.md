# zeroServer 后续完善实施方案

更新日期：2026-10-01。适用版本：`0.1.2-SNAPSHOT`。当前状态：`productionReady=false`。

本文把现有问题转化为可以分阶段实施、验证和回滚的开发计划。它是实施入口，不代替具体模块契约、API 文档或历史报告。当前代码能否使用，以[能力矩阵](capability-matrix.zh-CN.md)和对应模块测试为准。

## 1. 结论和目标

当前 zeroServer 的基础设施装配能力已经较完整：核心、运行时、Starter、Adapter、协议生成、事件、Actor、数据、缓存、网络和监控之间有清晰边界。主要缺口集中在游戏状态的分布式语义：Player、Room、Scene、World、Ranking 等本地服务仍主要由单 JVM 状态和本地 Actor lane 承载。

后续目标不是继续增加孤立模块，而是形成四条可验证的能力链：

```text
业务状态所有权
  -> Actor lane
  -> ownership / epoch fencing
  -> 远程路由
  -> checkpoint / recovery

远程 IO
  -> 受管执行域
  -> deadline / cancellation
  -> 有界队列
  -> 失败与未知结果处理

实时游戏链路
  -> 协议事件
  -> 场景/房间 Tick
  -> AOI/帧/广播
  -> 出站预算
  -> 重连与重同步

生产证据
  -> 本地契约
  -> 外部 Adapter
  -> 双 JVM 故障
  -> 容量长稳
  -> 发布与恢复
```

第一个目标是完成 Room 跨进程纵切片。它比直接改造开放世界更容易控制状态边界，又能覆盖 ownership、Actor、RPC、Repository、重连、幂等和恢复等关键语义。

## 2. 设计原则

### 2.1 先固定业务边界，再抽象基础设施

保留本地实现作为原型和测试实现。不要为了形式上的可插拔，把所有本地类机械改成空泛 SPI。只有当同一业务契约确实需要 local、external-test 和 production 三种实现时，才增加共享端口和契约测试。

### 2.2 业务状态必须有明确 owner

Repository 只负责存取，RPC 只负责传输，Actor 只负责执行域。三者都不能自动替代业务状态 owner。每个可迁移状态必须声明：

- owner identity。
- resource generation。
- process incarnation。
- lease / epoch。
- state version。
- checkpoint 位置。
- 接管和释放条件。

### 2.3 异步返回值不等于非阻塞

所有外部数据、RPC、服务发现和网络调用都必须明确执行语义。`CompletionStage` 只能表达完成方式，不能隐藏同步阻塞。具体契约见[受管远程 IO 契约](reference/remote-io-execution-contract.zh-CN.md)。

### 2.4 每个阶段必须有可复现出口

一个 focused test 只能证明局部语义；一个单节点外部测试不能证明集群高可用。所有阶段都要记录代码版本、运行环境、输入负载、失败注入、输出证据和未覆盖边界。

### 2.5 设计能力和交付能力分开标记

文档使用以下状态词：

| 状态 | 含义 |
| --- | --- |
| `implemented` | 当前代码和测试已经覆盖声明范围 |
| `minimum-slice` | 已有最小语义和 focused tests，生产范围仍有限 |
| `planned` | 已确定方案和验收标准，尚未交付 |
| `design-boundary` | 已有端口或设计边界，但实现不完整 |
| `not-proven` | 没有足够证据，不能对外承诺 |

## 3. 目标架构

后续业务纵切片统一采用下面的依赖关系：

```text
Client / GM / Internal Event
             |
             v
      Protocol / RPC Handler
             |
             v
      Business Command Port
             |
             v
     Owner Router + Actor Gateway
        |                    |
        v                    v
  Local Actor Lane      Remote Transport
        |                    |
        v                    v
   State Transition <- Idempotency / Fencing
        |
        +--> Checkpoint / Repository
        +--> Event / Audit / Metrics
        +--> Outbound Snapshot / Delta
```

业务模块不应直接依赖 Netty、Kafka client、数据库驱动、线程池或具体 Redis API。组合根负责把这些能力接到端口上，并在启动时报告有效 provider、资源所有权和安全边界。

## 4. 分阶段路线

### P0：完成 Room 跨进程纵切片

目标：用一条真实业务链证明 ownership 不只是独立基础设施，而是可以承载游戏状态。

范围：

- Room 状态 owner 和 owner route。
- Actor lane 写入。
- ownership lease / epoch fencing。
- Room snapshot 和 checkpoint。
- RPC 远程命令投递。
- 断线重连和幂等请求。
- 节点退出和新 owner 接管。
- 基础指标、审计和恢复证据。

不包含：完整匹配器、跨机房灾备、观战系统、奖励资产发放和全球房间调度。

验收出口：双 JVM 能完成创建、加入、开始、断线、owner 迁移、恢复、重连、结算幂等；旧 owner 的迟到写入全部被 fencing 拒绝。

详细计划见[Room 跨进程纵切片](guides/distributed-room-vertical-slice.zh-CN.md)。

### P1：统一远程 IO 执行语义

目标：避免 Actor lane、Netty IO 线程或 scheduler worker 被同步外部 IO 阻塞。

范围：

- blocking / non-blocking 分类。
- 受管 remote IO executor。
- queue budget 和拒绝策略。
- deadline、取消和关闭。
- retry、unknown write result 和恢复对账。
- MongoDB、Redis、PostgreSQL、Kafka、Nacos 的契约标注。

验收出口：任何 Adapter 都能说明调用线程、最大等待、取消边界、关闭行为和失败分类；业务测试能够证明 Actor lane 在外部 IO 未完成时仍可按规则处理其它 lane。

详细契约见[受管远程 IO 契约](reference/remote-io-execution-contract.zh-CN.md)。

### P1：建立场景化容量与长稳基线

目标：让性能结论与游戏类型、负载和执行域绑定。

优先场景：

1. RPG 登录、玩家加载和场景进入。
2. 场景移动、AOI 广播和慢客户端。
3. Room 对局 Tick、断线和结算。
4. 帧同步输入、迟到输入和重连。
5. NPC 区域 Tick 和行为超时。
6. Ranking 写入、赛季冻结和结算。
7. RPC pending、Kafka 重平衡和积压。
8. 持久化 flush、缓存热点和恢复。

验收出口：每类场景有固定工作负载、p50/p95/p99、吞吐、拒绝率、队列长度、内存和 GC 记录；没有环境和负载说明的数字不能进入 README 或发布材料。

详细标准见[游戏服务器开发与验收标准](operations/game-server-development-verification.zh-CN.md)。

### P1：统一配置与装配诊断

目标：让 Provider 实际看到的配置、诊断输出和生成项目文档保持一致。

范围：

- 文件、系统属性、环境变量和默认值的统一优先级。
- 脱敏值和来源展示。
- reloadable 标记。
- Provider/profile 冲突。
- 依赖闭包和资源拥有权。
- 未启用能力的风险提示。

验收出口：对同一配置运行 plan、diagnose 和 start，三者给出一致的有效值、来源、缺失项和失败原因。

### P2：补充轻量 RPC 传输

在 Kafka RPC 契约稳定后，再评审 TCP/HTTP 轻量实现。必须先完成 local、轻量传输和 Kafka 的共同契约测试，不以增加第二个实现为目标本身。

验收重点：request/response、oneway、broadcast、timeout、cancellation、late response、最大 in-flight、drain 和关闭。

### P2：运营、发布和恢复闭环

范围：

- 真实 TLS 和证书轮换。
- GM 身份、RBAC、审批和审计持久化。
- 生产日志 sink 和告警通道。
- 制品签名、SBOM、迁移实例。
- 备份、PITR、RPO/RTO 和回滚演练。

只有在安全、运营、容量和恢复证据全部具备后，才重新评估 `productionReady`。

## 5. 模块责任分配

| 工作项 | 首选模块 | 业务项目职责 |
| --- | --- | --- |
| Room owner 与迁移 | `zero-room`、`zero-ownership`、`zero-rpc` | 定义 Room snapshot、业务命令和结算幂等 |
| 远程 IO | `zero-runtime-bootstrap`、各 `zero-runtime-*` | 选择执行域、预算和业务 timeout |
| AOI / 广播预算 | `zero-aoi`、`zero-state-sync`、`zero-net` | 定义场景负载和慢客户端策略 |
| 帧同步 | `zero-frame-sync`、`zero-protocol` | 提供跨端确定性模拟和回放规则 |
| 数据恢复 | `zero-data`、各 Data Adapter | 提供 schema、版本迁移和未知写结果处理 |
| 运营审计 | `zero-gm`、`zero-log`、`zero-monitor` | 接入真实身份、审批、归档和告警 |
| 配置装配 | `zero-runtime`、`zero-codegen` | 维护应用自定义 provider 和配置样例 |
| 验收证据 | `scripts/`、`examples/`、`docs/operations/` | 提供真实环境、负载和失败注入材料 |

## 6. 每个实现任务的固定产物

涉及公共 API、线程、协议、RPC、存储、缓存、权限、日志、热更或模块依赖的任务，必须同时提交：

1. 设计说明：目标、非目标、边界和替代方案。
2. API/SPI 变化说明：新增、修改、废弃和迁移步骤。
3. 风险说明：并发、数据、安全、容量和回滚。
4. 实现代码和 focused tests。
5. 故障或恢复测试（如果涉及外部系统或状态迁移）。
6. 容量或长稳证据（如果改变热路径或调度语义）。
7. 能力矩阵、模块图、路线图或迁移记录同步。
8. Changelog 的用户可见变化。

## 7. 完成定义

一个阶段只有同时满足以下条件才算完成：

- 代码、测试和文档的状态词一致。
- 失败路径有 ErrorCode 和可观察结果。
- 队列、in-flight、缓存、连接和资源都有上限或关闭语义。
- 跨线程完成不会直接修改错误的 Actor 状态。
- 旧 owner、迟到消息、重复请求和取消都经过测试。
- 真实外部依赖未验证时，文档明确写 `not-proven`。
- 变更不会依赖本机私有文件或未跟踪日志。

## 8. 近期执行顺序

```text
1. Room 业务状态边界和 snapshot contract
2. 双 JVM owner / epoch / recovery 验收
3. 受管远程 IO contract 和阻塞审计
4. Room 迁移与重连长稳
5. 场景化性能基线
6. 配置与诊断收敛
7. 轻量 RPC 设计评审
8. 运营、发布和恢复闭环
```

不要在 Room 纵切片尚未完成前同时改造 Player、World、Ranking 和帧同步的分布式实现。先把一条链路做完整，才能复用已经验证的所有权、恢复、背压和观测模式。
