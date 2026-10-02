# Room 跨进程纵切片开发计划

更新日期：2026-10-01。适用版本：`0.1.2-SNAPSHOT`。当前状态：`planned`，不代表 Room 已具备跨进程生产能力。

本文定义 zeroServer 后续第一条真实业务纵切片。目标是把已有 Room 本地状态、Actor 执行域、ownership、RPC、Repository、恢复和观测能力连接起来，形成可复用的跨进程业务模式。

## 1. 为什么选择 Room

Room 的状态边界比开放世界和全局 Player 更清晰，通常包含：

- roomId。
- match/session 状态。
- 玩家席位。
- ready/start/finish 状态。
- 当前 Tick 或回合号。
- 断线重连窗口。
- 结算状态。

它可以验证 owner 迁移、checkpoint、迟到消息、幂等结算和重连，而不需要一开始就解决全地图 AOI、批量实体迁移和跨机房路由。

## 2. 业务边界

Room 纵切片只负责对局状态，不负责以下职责：

| 职责 | 归属 |
| --- | --- |
| 匹配候选生成 | `MatchService` 或业务应用 |
| 网络连接管理 | `zero-net` / session 层 |
| 玩家账号认证 | 账号或网关服务 |
| 房间状态修改 | `RoomService` + Room owner Actor |
| 持久化快照 | `Repository` / `DataService` |
| owner 路由和 fencing | `zero-ownership` |
| 远程投递 | `zero-rpc` / Actor gateway |
| 奖励发放 | `SettlementService` 或奖励服务 |
| GM 查询和操作 | `zero-gm` |

Room 不能直接持有 Netty channel、Kafka producer、数据库连接或业务线程池。

## 3. 目标状态模型

```text
CREATED
  -> JOINING
  -> READY
  -> RUNNING
  -> DISCONNECT_WINDOW
  -> RUNNING
  -> SETTLING
  -> CLOSED
```

所有状态变更必须在 Room owner 的 Actor lane 内完成。外部请求先变成带有 `roomId`、`commandId`、`requestId`、`traceId` 和 `expectedEpoch` 的命令，再由 owner 路由到本地或远程 Actor。

建议的逻辑状态字段：

```text
roomId
ownerService
ownerInstance
resourceGeneration
ownerEpoch
stateVersion
roomState
players[]
tickOrRound
reconnectDeadline
settlementStatus
lastCheckpointVersion
```

`ownerEpoch` 用于拒绝旧 owner 的迟到写入；`stateVersion` 用于 CAS；`requestId` 用于命令幂等；`resourceGeneration` 用于区分同一 roomId 的新旧生命周期。

## 4. 命令和结果边界

第一阶段建议只支持下面的命令：

| 命令 | 语义 | 幂等要求 |
| --- | --- | --- |
| `CreateRoom` | 创建 Room 并生成 owner 记录 | `requestId` 幂等 |
| `JoinRoom` | 占用席位并返回当前快照 | player + room 组合幂等 |
| `ReadyRoom` | 修改玩家准备状态 | commandId 幂等 |
| `StartRoom` | 从 READY 进入 RUNNING | state version + owner epoch |
| `SubmitInput` | 提交回合或 Tick 输入 | input sequence 幂等 |
| `Disconnect` | 进入重连窗口 | session generation fencing |
| `Reconnect` | 校验重连票据并恢复快照 | token + room generation |
| `SettleRoom` | 进入结算并生成 settlement id | settlement id 幂等 |
| `CloseRoom` | 释放 owner 和资源 | close token 幂等 |

返回结果必须区分：

- 已接受但尚未执行。
- 已在当前 owner 执行。
- 已转发到远程 owner。
- 被旧 epoch 拒绝。
- 被 state version 拒绝。
- 已处理过的幂等请求。
- Room 不存在或已关闭。
- 当前容量或队列不足。

## 5. 进程和资源关系

```text
Center / Discovery
        |
        v
Room route: roomId -> ownerInstance + epoch
        |
  +-----+------+
  |            |
  v            v
Local Room   Remote Room Gateway
  |            |
  v            v
Room Actor <- RPC command
        |
        +--> Snapshot store / checkpoint
        +--> Event / audit
        +--> Client broadcast
```

本地和远程调用必须共享同一套业务命令和结果契约。应用从单 JVM 拆成双 JVM 时，业务服务不应改写状态迁移逻辑，只替换 owner route 和 transport binding。

## 6. Owner 生命周期

### 6.1 创建

1. 生成唯一 `resourceGeneration`。
2. 申请 ownership lease。
3. 写入 owner route 和初始 state version。
4. 创建本地 Room Actor。
5. 发布 `ROOM_READY` 观测事件。

### 6.2 运行

- 所有状态写入必须在 owner lane 内完成。
- 远程命令必须携带 `ownerEpoch`。
- 每个命令必须在有界队列中排队。
- 超过预算时明确返回拒绝，不无限积压。
- checkpoint 不得在 Actor lane 中同步等待远程 IO。

### 6.3 排空

1. 标记 route 为 `DRAINING`。
2. 拒绝新的非重连写入。
3. 等待已接受命令达到安全边界。
4. 写入最终 checkpoint。
5. 释放 lease 或完成 epoch 转移。
6. 关闭本地 Room Actor。

### 6.4 接管

1. 新进程获取更高 owner epoch。
2. 读取最后一个有效 checkpoint。
3. 校验 resource generation 和 state version。
4. 重建 Room Actor 状态。
5. 发布新 route。
6. 旧 owner 的命令全部被 fencing 拒绝。
7. 允许重连和幂等命令继续执行。

## 7. Snapshot 和恢复

Room snapshot 必须是版本化、可校验、可迁移的结构。至少包含：

- schema version。
- codec id。
- resource generation。
- owner epoch。
- state version。
- room state。
- player seat state。
- tick/round position。
- reconnect window。
- settlement status。
- last applied command sequence。

snapshot codec 不应依赖 Netty ByteBuf 或数据库驱动。落库由 Repository Adapter 负责，业务状态的序列化由 Room 自己声明。

恢复必须能区分：

- 没有 checkpoint。
- checkpoint 版本过旧。
- codec 不支持。
- state version 冲突。
- owner epoch 已被新 owner 抢占。
- snapshot 校验失败。

任何恢复失败都要进入明确的失败状态和告警，不能继续以空房间状态运行。

## 8. 重连和结算

### 重连

重连票据至少绑定：

- playerId。
- roomId。
- resourceGeneration。
- token expiry。
- session generation。
- 允许的 owner route。

重连流程必须先验证票据，再向当前 owner 请求快照。旧 owner 不得继续接受重连。

### 结算

结算必须使用独立 `settlementId`，并区分：

```text
CREATED -> CLAIMED -> EXECUTING -> SUCCEEDED
                              \-> FAILED / COMPENSATION_REQUIRED
```

Room 只记录结算事实和幂等键，不直接假设奖励已经发放。奖励服务返回未知结果时，必须通过对账或补偿处理，不能自动再次发奖。

## 9. 故障矩阵

| 故障 | 预期行为 | 必须观测 |
| --- | --- | --- |
| 旧 owner 仍发送命令 | 被 epoch fencing 拒绝 | `stale_epoch_rejected` |
| RPC 重复投递 | 由 requestId / commandId 幂等 | `duplicate_command` |
| RPC 迟到响应 | 不覆盖新 state version | `late_response_ignored` |
| owner 进程退出 | 新 owner 接管或进入恢复失败 | `owner_failover` |
| checkpoint 读取失败 | Room 进入恢复失败，不创建空状态 | `recovery_failed` |
| 远程 IO 队列满 | 明确拒绝或降级 | `remote_io_rejected` |
| 玩家断线 | 进入有限重连窗口 | `reconnect_window_opened` |
| 结算未知结果 | 进入对账/补偿状态 | `settlement_unknown` |
| Room 关闭重复请求 | 返回幂等结果 | `close_duplicate` |

## 10. 实施步骤

### 阶段 A：本地契约

- 保留现有本地 Room API。
- 增加版本化 Room snapshot codec。
- 为命令补齐 requestId、commandId、traceId、ownerEpoch。
- 增加本地 stale epoch、重复命令和恢复测试。

### 阶段 B：双 JVM ownership

- 使用 `zero-ownership-postgresql` 保存 owner、state 和 migration metadata。
- 两个独立 JVM 共享 Room contract。
- 模拟 owner 退出、抢占、旧消息和重连。
- 记录完整运行 manifest、日志和清理结果。

### 阶段 C：真实 RPC 和外部数据

- 通过现有 RPC gateway 投递 Room command。
- 通过受管 remote IO 执行 checkpoint 读写。
- 对 Kafka、PostgreSQL 的重启和连接失败进行故障注入。
- 不把构建成功或单次请求成功写成生产证据。

### 阶段 D：容量和长稳

- 固定房间数、玩家数、Tick 频率和命令速率。
- 测量 owner lane queue、RPC pending、checkpoint latency、重连耗时和内存。
- 至少执行 30 分钟稳定运行，再根据项目需要扩展到 2 小时或更长。

## 11. 完成标准

Room 纵切片只有满足以下条件才可以标记为 `minimum-slice`：

- 双 JVM 能完成 owner 创建、接管和释放。
- 旧 epoch 的写入不会改变新 owner 状态。
- snapshot 能恢复并校验 schema、generation 和 state version。
- 重复命令、重复结算和重复关闭有稳定结果。
- RPC、数据库、队列和重连都有明确上限。
- Actor lane 不同步等待远程 IO。
- 失败、拒绝、恢复和补偿均有 ErrorCode、日志和指标。
- 所有外部环境、版本、命令和清理步骤有可复现记录。

完成这些条件后，仍只能说明 Room 达到受控集成级别。跨机房、集群复制、全量匹配和生产容量仍需单独验证。
