# 房间本地契约与扩展边界

当前状态：`implemented-local-minimum-slice / productionReady=false`。对应模块：`zero-room`，入口：`LocalRoomService`。使用方式见[可运行示例](../../examples/room-game/README.md)，整体状态见[能力矩阵](../capability-matrix.zh-CN.md)。

本页先列当前本地限制和验证口径。末尾保留历史设计输入，其中候选类型、状态和跨服行为可能尚未实现，不应直接作为现行 API 使用。生产扩展仍需评审公共 API、Actor 所有权和数据边界；`requiresConfirmation=true`。

<!-- contract-id=room-component-minimum-contract -->

## 本地容量与验证

状态：`implemented-local-minimum-slice`；`productionReady=false`。

本文记录 `zero-room` 当前单 JVM、本地内存实现的默认容量和拒绝语义。数值是可复现的本地验证边界，不是吞吐、延迟、SLA 或生产容量承诺。

### 默认限制

| 维度 | 默认值 | 说明 |
| --- | ---: | --- |
| 单房间成员数 | 由 `create` 的 `capacity` 指定（示例为 2） | 必须为正数；已占用席位达到上限后拒绝新成员 |
| 断线重连窗口 | 由 `create` 的 `reconnectWindowMillis` 指定（示例为 5000 ms） | 窗口内允许原成员重连；窗口外拒绝 |
| 房间总数 | 当前服务实例按已创建房间管理 | 当前 API 不提供跨实例全局配额；不应据此推导集群容量 |
| 事件/快照成员 | 快照为不可变成员列表 | 示例输出 room sequence 和成员快照；内存历史仅保留最近 1024 条，持久化由业务事件消费者承担 |
| 命令积压 | 由 Actor scheduler 的本地调度能力约束 | 不宣称固定吞吐或无界排队；过载行为属于运行时调度边界 |

### 拒绝策略

- 非法房间配置、重复创建和不存在房间：立即抛出稳定的 `IllegalArgumentException` 或 `IllegalStateException`。
- 房间已满：拒绝新加入，不静默踢出已有成员。
- 非 `WAITING` 状态加入、未满足全员 ready 的 start、非 `RUNNING` 状态 settle：拒绝状态变更。
- 断线重连必须使用原成员且位于时间窗口内；过期请求拒绝。
- settlement 使用幂等 key：同 key 重放返回相同结果；不同 key 的重复结算拒绝。
- 示例中的快照和事件序列只输出本地内存状态；不声称事件已持久化或已广播。

### 负载口径

验证负载是单 JVM、单进程、内存对象、room lane 串行命令：两名成员走一条完整生命周期，并打印每次状态快照。测试关注状态转换、顺序、重连窗口和结算幂等，不是 benchmark，也不代表并发房间数、QPS、P99、网络带宽或稳定运行时长。

不包含：外部数据库/缓存/MQ、网络连接、匹配、观战、跨服路由、持久化恢复、广播背压、故障转移和生产部署。

### 证据 marker

```text
room-game=ok|mode=local|events=created,join,ready,start,disconnect,reconnect,settle,close|productionReady=false
```

该 marker 只证明示例命令成功运行，不把示例或单元测试等同于生产就绪。

<details>
<summary>历史设计输入与扩展候选（不代表现行 API）</summary>

以下内容保留最初的设计范围、备选方案和测试建议；其中“缺少”“下一步”等表述对应设计时点。当前已实现范围以上面的本地契约、示例和源码为准。

## 1. 定位

现有 `scripts/NewLocalGame.java --template room` 已能生成房间 / 对战小局原型，帮助用户跑通：

- `.si` 协议。
- DTO / codec / BO / dispatcher 生成。
- 本地 Actor lane 串行修改房间状态。
- 本地日志和指标输出。
- 创建房间、加入、准备、开始和提交帧输入。

正式框架能力还缺少：

- 明确的房间生命周期。
- 成员状态与断线恢复语义。
- 广播顺序和背压边界。
- 结算幂等和重复提交保护。
- 匹配 ticket、取消、成局和失败语义。
- 跨服房间路由候选边界。

本文的价值是把这些缺口整理成“下一步实现前必须确认什么”，而不是直接把模板升级成生产承诺。

## 2. 范围

本草案覆盖：

- 房间状态候选。
- 成员状态候选。
- 命令与事件候选。
- Actor / 线程归属候选。
- 广播顺序候选。
- 结算幂等候选。
- 断线、重连、离开、房主迁移候选。
- 匹配边界候选。
- 跨服房间边界候选。
- 日志、指标、TraceId 和 ErrorCode 候选。
- focused tests。

本草案不覆盖：

- 不创建 `zero-room`。
- 不创建 `zero-matchmaking`。
- 不冻结正式 Java API。
- 不冻结协议 ID、frame 格式或 codegen 规则。
- 不实现跨服房间。
- 不实现复杂 MMR、ELO、段位或公平匹配算法。
- 不承诺生产容量、延迟、SLA 或压测指标。
- 不更新 `docs/module-map.md` 声明候选模块已经存在。

## 3. 候选模块边界

候选模块只作为后续确认问题，不代表本仓库当前已经存在这些模块。

| 候选模块 | 候选职责 | 禁止越界 |
| --- | --- | --- |
| `zero-room` | 房间生命周期、成员状态、房间命令、广播顺序、结算幂等、断线恢复 | 不直接依赖 MongoDB、Redis、Kafka、Nacos；不直接创建线程池；不在 IO 线程执行业务阻塞逻辑 |
| `zero-matchmaking` | 匹配 ticket、队列、取消、成局、超时和简单策略 | 不把复杂排行 / 段位算法写死为核心规则；不绕过 Actor / message 边界直接改房间状态 |
| `zero-game` 增强 | 承载房间执行业务域和 Actor gateway 协作 | 不破坏现有玩家 / 场景原型 API |
| `zero-net` / `zero-rpc` 协作 | 承载客户端入口、跨服路由和远程 Actor 消息 | 不在未确认前改变协议格式、RPC 语义或服务发现策略 |

推荐依赖方向仍应保持：

```text
zero-room / zero-matchmaking -> zero-game / zero-actor / zero-event / zero-protocol / zero-core
zero-room / zero-matchmaking -> zero-log / zero-monitor
adapter -> zero-room / zero-matchmaking only through SPI or service abstraction
starter -> zero-room / zero-matchmaking
```

正式创建模块或改变依赖方向前，必须同步更新 `docs/module-map.md`。

## 4. 房间生命周期候选

房间状态建议先控制在最小集合，避免早期把过多业务规则写死。

| 状态 | 含义 | 允许的典型进入动作 | 允许的典型退出动作 |
| --- | --- | --- | --- |
| `CREATED` | 房间对象已创建，但尚未开放加入 | `createRoom` | `openRoom`、`closeRoom` |
| `WAITING` | 等待成员加入或准备 | `openRoom`、`createAndOpenRoom` | `ready`、`startRoom`、`closeRoom` |
| `READY_CHECK` | 等待成员准备确认 | `ready` 达到策略条件 | `startRoom`、`cancelReady`、`closeRoom` |
| `STARTING` | 开始前短暂过渡，用于锁定成员、生成 seed 或分配资源 | `startRoom` | `markRunning`、`closeRoom` |
| `RUNNING` | 房间对局进行中 | `markRunning` | `settleRoom`、`closeRoom` |
| `SETTLING` | 结算中，拒绝新的对局输入，允许幂等查询结算结果 | `settleRoom` | `markSettled`、`closeRoom` |
| `SETTLED` | 结算完成，结果可重复读取 | `markSettled` | `closeRoom` |
| `CLOSED` | 房间关闭，状态不可再变更 | `closeRoom` | 无 |

候选规则：

- `CLOSED` 必须是终态。
- `SETTLED` 后重复结算必须返回相同结果或明确的幂等拒绝。
- `RUNNING` 后默认拒绝普通 `joinRoom`，观战入口必须单独建模。
- 状态流转必须由房间 Actor / lane 串行执行。

## 5. 成员状态候选

成员状态只描述成员在房间内的关系，不替代全局玩家在线状态。

| 状态 | 含义 | 注意事项 |
| --- | --- | --- |
| `JOINED` | 成员已加入房间，未准备 | 可离开、准备或断线 |
| `READY` | 成员已准备 | 可取消准备，开始后进入 `PLAYING` |
| `PLAYING` | 成员参与当前对局 | 默认不可普通离开，离开策略由业务确认 |
| `DISCONNECTED` | 成员连接断开但保留席位 | 保留时长、是否自动托管、是否影响结算需要确认 |
| `LEFT` | 成员主动或被动离开房间 | 是否可重入取决于房间状态和业务策略 |
| `SPECTATING` | 成员观战，不参与结算 | 观战可见范围和带宽策略需要单独确认 |

候选规则：

- 成员状态变更必须带 `traceId`。
- 玩家全局在线状态仍应归属 player actor。
- 房间内成员状态归属 room actor / lane。
- player actor 与 room actor 之间只能通过消息协作，不能直接跨 Actor 修改状态。

## 6. 命令与事件候选

命令候选：

| 命令 | 主要目的 | 高风险确认点 |
| --- | --- | --- |
| `createRoom` | 创建房间 | roomId 生成、owner 绑定、重复创建幂等 |
| `joinRoom` | 加入房间 | 容量、状态校验、重复加入、黑名单 / 权限 |
| `leaveRoom` | 离开房间 | 对局中离开、房主迁移、席位保留 |
| `ready` | 设置准备 | 是否所有成员准备才可开始 |
| `cancelReady` | 取消准备 | READY_CHECK 是否允许回退 |
| `startRoom` | 开始房间 | 成员锁定、seed、资源预热、开始失败回滚 |
| `submitInput` | 提交对局输入 | 顺序、去重、迟到输入处理 |
| `reconnect` | 重连回房 | 断线保留时间、状态补发、重复连接 |
| `transferOwner` | 转移房主 | 自动迁移还是显式操作 |
| `settleRoom` | 提交结算 | 幂等键、重复结算、结果可信来源 |
| `closeRoom` | 关闭房间 | 终态、资源释放、未结算关闭 |

事件候选：

- `RoomCreated`
- `RoomJoined`
- `RoomLeft`
- `RoomReadyChanged`
- `RoomStarted`
- `RoomInputAccepted`
- `RoomMemberDisconnected`
- `RoomMemberReconnected`
- `RoomOwnerTransferred`
- `RoomSettlementRequested`
- `RoomSettled`
- `RoomClosed`
- `MatchTicketCreated`
- `MatchTicketCanceled`
- `MatchFound`
- `MatchFailed`

事件必须携带：

- `traceId`
- `roomId`
- `roomSeq`
- `operatorUid` 或系统来源
- `occurredAt`
- 明确的结果状态或 ErrorCode

## 7. Actor 与线程归属

候选归属：

- 房间状态绑定 room actor / room lane。
- 玩家全局状态绑定 player actor。
- 匹配队列可以绑定 matchmaking actor / queue lane。
- 远程 IO、存储和 RPC 不得在 room actor 中阻塞执行。
- 跨 Actor 修改必须通过消息。
- 定时任务必须可取消、可观测、可限流。

候选流程：

```text
client / rpc / timer
  -> command adapter
  -> validate immutable input
  -> dispatch message to room actor
  -> mutate room state in room actor
  -> emit room event with traceId and roomSeq
  -> enqueue broadcast / persistence / metrics side effect
  -> side effect executes outside room actor when it may block
```

需要避免：

- IO 线程直接执行业务阻塞逻辑。
- room actor 内直接调用远程 RPC 并等待。
- player actor 与 room actor 相互持锁等待。
- 为每个房间直接创建线程池。
- 业务代码绕过消息直接改另一个 Actor 的状态。

## 8. 顺序与幂等

候选顺序规则：

- 每个房间维护单调递增 `roomSeq`。
- 房间内状态变更事件按 `roomSeq` 发布。
- 广播默认按同一 room actor 的提交顺序发送。
- 跨服广播只保证同一 roomId 的逻辑顺序候选，不承诺不同 roomId 之间全局有序。
- 客户端重复输入必须通过 command id、input seq 或业务幂等键去重。

候选结算规则：

- `settlementId` 或 `idempotencyKey` 必须由业务或框架明确生成。
- 同一房间同一 `settlementId` 重复提交时，不得重复发奖、重复写结算主记录或重复触发不可逆操作。
- 结算进入 `SETTLING` 后，应拒绝新的对局输入。
- `SETTLED` 后重复查询应返回同一结算结果快照。
- 结算失败后的重试、人工修正和 GM 干预属于高风险能力，需另行确认。

## 9. 断线、重连与房主迁移

候选断线规则：

- 连接断开不等同于成员离开。
- `DISCONNECTED` 成员是否保留席位，由房间类型和配置决定。
- 保留期间可允许 `reconnect` 回到原状态。
- 保留超时可转为 `LEFT`、托管、判负或保持旁观，必须由业务显式选择。

候选房主迁移规则：

- 房主离开或断线超时后，选择下一个可用成员。
- 迁移顺序候选：加入顺序、准备优先、在线优先、业务权重。
- 房主迁移必须产生 `RoomOwnerTransferred` 事件。
- 没有可迁移成员时，房间可进入 `CLOSED` 或等待超时关闭。

需要确认：

- 断线保留默认时长。
- `RUNNING` 中断线是否继续占用席位。
- 房主断线是否立即迁移，还是等待保留超时。
- 断线成员重连后是否需要补发快照、事件日志或只发送当前状态。

## 10. 匹配边界

匹配组件候选最小能力：

- `createTicket`
- `cancelTicket`
- `ticketTimeout`
- `matchFound`
- `matchFailed`

ticket 候选字段：

- `ticketId`
- `uid`
- `queueId`
- `partyId`
- `matchAttrs`
- `traceId`
- `createdAt`
- `timeoutAt`

首批只建议确认简单策略：

- 按队列 FIFO。
- 按人数凑齐。
- 按基础标签过滤，例如 mode、region、version。
- 超时失败或降级匹配。

暂不冻结：

- MMR。
- ELO。
- 段位。
- 复杂公平性。
- 跨区延迟优化。
- 多队伍复杂组合。

匹配成功后，只应通过消息请求 room actor 创建或打开房间，不应由 matchmaking 直接修改房间内部状态。

## 11. 跨服边界

跨服房间属于高风险边界，本草案只保留候选约束：

- 跨服房间需要依赖 RPC、服务发现和远程 Actor 路由的正式确认。
- 同一 roomId 同一时刻只能有一个权威 room owner actor。
- 迁移或容灾时必须定义 fencing token、epoch 或 owner version。
- 远程命令必须携带 `traceId`、`timeoutAt` 和幂等键。
- 超过 `timeoutAt` 的远程命令应拒绝执行。
- 跨服广播必须明确“至少一次 / 至多一次 / 可重复去重”的交付语义。

首批建议：

- local/prototype 仍保持单进程。
- 正式 `zero-room` 首轮可以只实现单 JVM 或单 owner actor 语义。
- 跨服能力先作为接口边界和 focused tests 草案，不直接实现。

## 12. 数据与缓存边界

候选原则：

- 房间运行中状态优先常驻内存。
- 持久化应通过统一 `Repository` / `DataService` 抽象。
- 可恢复房间需要快照或事件日志策略，但首批不强制。
- 结算结果属于更高可靠性数据，应有幂等写入和失败保留策略。
- Redis 可用于短期状态、匹配队列或排行榜协作，但 `zero-room` 不得直接依赖 Redis adapter。

需要确认：

- 房间是否需要持久化。
- 房间事件是否需要追加日志。
- 结算失败时是否保持房间内存状态并降级服务。
- 匹配 ticket 是否允许只存在内存，还是需要 Redis / DB 兜底。

## 13. 日志、指标与 TraceId

日志候选分类：

- 业务日志：房间创建、加入、开始、结算。
- 性能日志：命令处理耗时、队列等待时间、广播耗时。
- 错误日志：状态非法、结算失败、路由失败。
- 玩家行为日志：是否记录需业务确认，不应默认把所有 room event 当行为日志。

基础字段候选：

- `traceId`
- `roomId`
- `roomSeq`
- `uid`
- `command`
- `fromState`
- `toState`
- `errorCode`
- `durationMs`

指标候选：

- `zero_room_active_total`
- `zero_room_command_total`
- `zero_room_command_failed_total`
- `zero_room_command_duration_seconds`
- `zero_room_actor_queue_size`
- `zero_room_broadcast_total`
- `zero_room_settlement_total`
- `zero_match_ticket_active_total`
- `zero_match_ticket_timeout_total`

指标标签原则：

- 标签必须低基数。
- 默认不把 `playerId`、`uid`、`roomId` 作为 Prometheus 标签。
- 可选标签候选：`roomType`、`command`、`state`、`result`、`serverRole`。

## 14. ErrorCode 候选分类

候选分类只用于后续确认，不修改当前 ErrorCode 结构。

- `ROOM_NOT_FOUND`
- `ROOM_ALREADY_CLOSED`
- `ROOM_STATE_INVALID`
- `ROOM_FULL`
- `ROOM_MEMBER_NOT_FOUND`
- `ROOM_MEMBER_ALREADY_JOINED`
- `ROOM_MEMBER_NOT_READY`
- `ROOM_JOIN_REJECTED`
- `ROOM_START_REJECTED`
- `ROOM_INPUT_REJECTED`
- `ROOM_SETTLEMENT_DUPLICATE`
- `ROOM_SETTLEMENT_FAILED`
- `ROOM_OWNER_TRANSFER_FAILED`
- `MATCH_TICKET_NOT_FOUND`
- `MATCH_TICKET_CANCELED`
- `MATCH_TIMEOUT`
- `MATCH_FAILED`

对外错误必须绑定 ErrorCode；错误日志也必须绑定 ErrorCode。

## 15. Focused Tests

后续正式实现前，建议先确认以下测试口径：

| 测试名 | 验证点 |
| --- | --- |
| `roomLifecycleHappyPath` | 创建、加入、准备、开始、结算、关闭正常流转 |
| `joinRejectedAfterRunning` | `RUNNING` 后普通加入被拒绝 |
| `readyAllMembersAllowsStart` | 全员准备满足开始条件 |
| `disconnectKeepsMemberRecoverable` | 断线进入可恢复状态并可重连 |
| `ownerMigratesOnLeave` | 房主离开后按策略迁移 |
| `broadcastOrderFollowsRoomSequence` | 广播顺序跟随 `roomSeq` |
| `settlementIsIdempotent` | 重复结算不重复发奖或重复写入 |
| `crossActorMutationRejectedOrForcedThroughMessage` | 跨 Actor 修改必须通过消息 |
| `matchTicketCancelIsIdempotent` | 重复取消 ticket 不产生异常副作用 |
| `matchFoundCreatesRoomThroughMessage` | 匹配成功通过消息请求房间创建 |

## 16. 高风险确认问题

进入实现前必须暂停并确认：

- 是否允许创建 `zero-room` 和 `zero-matchmaking`？
- 是否允许新增公共 API / SPI？兼容策略是什么？
- 是否需要新增协议字段、协议 ID 或 codegen 规则？
- 房间状态机是否采用本文候选状态？
- 成员状态是否采用本文候选状态？
- 房间状态归属 room actor 是否作为硬约束？
- 匹配队列和房间创建之间如何通过消息协作？
- 断线保留、房主迁移、观战和结算默认策略是什么？
- 结算幂等键由框架生成还是业务提供？
- 跨服房间是否进入第一批范围？
- 哪些 ErrorCode、日志字段和指标必须首批冻结？
- 是否需要同步更新 `docs/module-map.md`？

## 17. 推荐推进顺序

```text
room scaffold
  -> RunLocalScaffold
  -> 阅读生成项目的 BUSINESS_GUIDE.md / NEXT_STEPS.md
  -> docs/reference/room-component-minimum-contract.zh-CN.md
  -> 提交 GitHub Design Proposal
  -> 维护者评审 API / 线程 / 存储 / 广播与兼容边界
  -> focused tests
  -> 最小 runtime 实现
  -> docs/module-map.md 同步
  -> quickstart / examples 同步
  -> performance evidence
```

## 18. 不证明什么

本文不证明：

- `zero-room` 已经存在。
- `zero-matchmaking` 已经存在。
- 房间 API 已冻结。
- 跨服房间已可用。
- 匹配算法已完成。
- 生产容量或延迟达标。
- 用户已经确认高风险实现。

本文只证明：房间 / 匹配正式化前，已经有一份可被 doctor、readiness 和 implementation slice selector 发现的最小契约草案。

</details>


## 2026-09-17 报告核实修订

leave 后立即移除成员，LEFT 历史通过事件表达，不进入现有成员快照。close 保留最终查询状态；业务完成归档后调用 destroy(id) 释放已关闭房间，之后查询/命令返回不存在。事件历史仅保留最近 1024 条，droppedEventCount 可查询淘汰量。事件消费者失败会向调用方传播统一异常；已经提交的状态转换不回滚。
