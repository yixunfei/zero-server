# 开放世界 / 分片迁移最小契约草案

状态：`draft`

需要确认：`requiresConfirmation=true`

关联切片：`world-shard-minimum-contract`

本文是 `world-shard` 从 local/prototype 模板走向正式组件前的最小契约输入。它只描述候选语义、风险边界和 focused tests，不创建 `zero-world` / `zero-shard` 模块，不冻结公共 API，不改变协议、RPC、存储、缓存、日志字段、指标标签或 ErrorCode 结构。

## 1. 背景

现有 `scripts/NewLocalGame.java --template world-shard` 已能生成开放世界 / 分片迁移原型，帮助用户跑通：

- `WorldShard.si` 协议。
- generated DTO / codec / BO / dispatcher。
- 本地 Actor lane 串行修改 world / shard 状态。
- 进入世界、实体移动、分片迁移和状态查询。
- 本地日志和指标输出。

它的定位是让业务团队快速理解“开放世界分片类需求”在 zeroServer 中的最小形态，不代表正式 WorldShard、ScenePartition、跨进程迁移、可靠状态交接、AOI 拼接或一致性协议已经冻结。

正式开放世界 / 分片能力至少会影响：

- 世界、分片、区域和实体归属模型。
- 分片迁移状态机。
- 源分片冻结、目标分片接管和失败恢复。
- 跨 Actor / 跨 JVM / 跨服 RPC 路由。
- AOI 拼接、跨分片可见性和广播顺序。
- 状态快照、版本号、Repository / Cache / Redis 边界。
- GM 强制迁移、重平衡、审计和 dry-run。
- ErrorCode、日志字段、指标标签和告警规则。

本文的价值是把这些缺口整理成“下一步实现前必须确认什么”，而不是直接把 `world-shard` 模板升级成生产承诺。

## 2. 最小目标

本草案只覆盖候选契约：

- 世界模型候选。
- 分片模型候选。
- 实体归属候选。
- 迁移状态机候选。
- 状态交接候选。
- 跨服路由候选。
- AOI 拼接和广播一致性候选。
- 数据、缓存、日志、指标和 focused tests 候选。

## 3. 非目标

- 不创建 `zero-world`。
- 不创建 `zero-shard`。
- 不冻结 `WorldService`、`ShardService`、`MigrationService` 或 `ScenePartitionService` API。
- 不修改 `world-shard` 本地模板生成代码。
- 不实现跨进程迁移。
- 不实现可靠状态交接。
- 不实现跨服广播。
- 不实现 AOI 拼接算法。
- 不实现一致性协议、分布式锁或补偿任务。
- 不修改 `docs/module-map.md` 声称候选模块已存在。

## 4. 术语

| 术语 | 含义 | 边界 |
| --- | --- | --- |
| World | 一个逻辑开放世界实例 | 不等同于一个进程 |
| Shard | World 内的状态承载分片 | 不等同于固定物理服务器 |
| Region | 可选的空间区块或 AOI 网格 | 不要求首版实现空间索引 |
| Entity | 玩家、NPC、怪物、物件等世界实体 | 必须有唯一归属 |
| Owner Shard | 当前拥有实体写权限的分片 | 跨 shard 修改必须走迁移或消息 |
| Migration | 实体从源 shard 转移到目标 shard 的流程 | 必须可观测、可超时、可恢复 |
| Handoff | 状态交接包 | 不等同于最终落库成功 |
| Visibility Stitching | 跨 shard 可见性拼接 | 不等同于全局广播 |
| Route Epoch | 分片路由版本 | 用于拒绝过期路由和重复迁移 |

## 5. 候选模块职责

后续若用户确认正式模块，候选职责可参考：

| 候选模块 | 候选职责 | 禁止事项 |
| --- | --- | --- |
| `zero-world` | World 生命周期、实体归属、分片编排、迁移协调候选 | 不直接依赖具体 Nacos / Kafka / Redis 实现 |
| `zero-shard` | Shard 状态、迁移接收、路由 epoch、局部广播候选 | 不绕过 Actor lane 修改实体状态 |
| `zero-scene` 协作 | 场景进入、移动、实体查询、局部 AOI | 不把 local scene 原型直接宣称为开放世界生产实现 |
| `zero-actor` 协作 | owner shard actor、entity actor、migration actor 的执行归属 | 不允许跨 Actor 直接引用修改 |
| `zero-rpc` 协作 | 跨服迁移、路由查询、广播转发候选 | 不在 Actor 线程执行不可控远程 IO |
| `zero-data` 协作 | 世界元数据、分片元数据、迁移记录、实体快照 | 不绕过 Repository / DataService 抽象 |
| `zero-cache` 协作 | 路由缓存、实体短期状态、迁移幂等记录 | 不把 cache 命中当作唯一事实来源 |
| `zero-log` / `zero-monitor` 协作 | 迁移日志、性能日志、安全审计、指标 | 不输出高基数标签 |

## 6. 世界模型候选

首版正式契约建议先区分逻辑世界和物理承载：

| 字段 | 类型候选 | 说明 |
| --- | --- | --- |
| `worldId` | string / long | 逻辑世界 ID |
| `worldType` | enum | 大世界、副本世界、活动世界、跨服世界 |
| `status` | enum | `CREATING`、`OPEN`、`DRAINING`、`CLOSING`、`CLOSED` |
| `routeEpoch` | long | 路由版本，世界级变更时递增 |
| `shardPolicy` | enum | 固定分片、动态扩缩、按区域、按负载 |
| `createdAt` | long | 创建时间 |
| `updatedAt` | long | 更新时间 |

候选规则：

- `worldId` 必须稳定，不依赖物理进程地址。
- `routeEpoch` 必须单调递增。
- `DRAINING` 状态下应拒绝新进入，但允许已有实体迁出。
- `CLOSING` 状态下应拒绝普通移动和迁移，除非是恢复或清理任务。
- 世界状态变化必须记录审计或运行日志。

## 7. 分片模型候选

| 字段 | 类型候选 | 说明 |
| --- | --- | --- |
| `shardId` | string | 分片 ID |
| `worldId` | string / long | 所属世界 |
| `nodeId` | string | 当前承载节点 |
| `regionSet` | set | 可选空间区块集合 |
| `status` | enum | `BOOTING`、`ACTIVE`、`DRAINING`、`RECOVERING`、`OFFLINE` |
| `capacityHint` | int | 容量提示，不是硬承诺 |
| `routeEpoch` | long | 分片路由版本 |
| `heartbeatAt` | long | 最近心跳时间 |

候选规则：

- `ACTIVE` 分片才可接收普通进入和迁移。
- `DRAINING` 分片不接收新实体，只允许迁出。
- `RECOVERING` 分片必须拒绝普通写入，直到状态恢复完成。
- 分片路由变更必须让旧 route epoch 的迁移请求可被拒绝。
- `nodeId` 不得暴露敏感主机信息到业务日志。

## 8. 实体归属候选

实体必须有唯一写归属：

```text
entityId
  -> worldId
  -> ownerShardId
  -> ownerActorLane
```

候选字段：

| 字段 | 类型候选 | 说明 |
| --- | --- | --- |
| `entityId` | long / string | 实体唯一 ID |
| `entityType` | enum | player、npc、monster、object |
| `worldId` | string / long | 所属世界 |
| `ownerShardId` | string | 当前写归属分片 |
| `position` | x/y/z or x/y | 世界坐标 |
| `stateVersion` | long | 实体状态版本 |
| `migrationId` | string | 正在迁移时的幂等 ID |
| `routeEpoch` | long | 当前路由版本 |

候选规则：

- 同一实体同一时刻只能有一个 `ownerShardId` 具备写权限。
- 迁移中实体应进入 `MIGRATING_OUT` 或 `MIGRATING_IN` 之类的临时状态。
- 源 shard 冻结后不得继续接受普通移动。
- 目标 shard 接管后必须拒绝旧 route epoch 的写入。
- 查询可以读本地快照，但响应必须标明快照版本或可能过期边界。

## 9. 迁移状态机候选

最小迁移流程建议从单实体迁移开始：

```text
REQUESTED
  -> SOURCE_LOCKED
  -> HANDOFF_SENT
  -> TARGET_PREPARED
  -> TARGET_COMMITTED
  -> SOURCE_RELEASED
  -> COMPLETED
```

异常路径：

```text
REQUESTED
  -> REJECTED

SOURCE_LOCKED
  -> ROLLBACK_SOURCE
  -> FAILED

HANDOFF_SENT
  -> UNKNOWN
  -> RECOVERING
  -> COMPLETED or FAILED
```

候选状态：

| 状态 | 含义 |
| --- | --- |
| `REQUESTED` | 已提交迁移请求 |
| `REJECTED` | 前置校验失败 |
| `SOURCE_LOCKED` | 源 shard 已冻结实体写入 |
| `HANDOFF_SENT` | 已发送状态交接包 |
| `TARGET_PREPARED` | 目标 shard 已校验并准备接收 |
| `TARGET_COMMITTED` | 目标 shard 已写入接管状态 |
| `SOURCE_RELEASED` | 源 shard 已释放旧归属 |
| `COMPLETED` | 迁移完成 |
| `ROLLBACK_SOURCE` | 源 shard 回滚中 |
| `RECOVERING` | 迁移结果未知，恢复任务接管 |
| `FAILED` | 迁移失败且已保留现场 |

候选规则：

- 每次迁移必须有 `migrationId`。
- `migrationId` 必须用于幂等去重。
- 源 shard lock 必须有超时。
- 目标 shard commit 必须校验 route epoch。
- 任一阶段失败必须保留迁移记录，不能只打印日志后继续。
- `UNKNOWN` / `RECOVERING` 是分布式边界竞争的正常状态，不能假装不存在。

## 10. 迁移命令候选

后续正式 API 设计前，可先确认命令语义：

| 命令 | 候选语义 | 线程归属 |
| --- | --- | --- |
| `enterWorld` | 实体进入世界并选择初始 shard | world / shard actor |
| `moveEntity` | owner shard 内移动 | owner shard actor |
| `requestMigration` | 请求从源 shard 迁移到目标 shard | source shard actor |
| `prepareMigration` | 目标 shard 准备接收 handoff | target shard actor |
| `commitMigration` | 目标 shard 接管实体 | target shard actor |
| `releaseSource` | 源 shard 释放旧归属 | source shard actor |
| `queryEntity` | 查询实体当前归属和快照 | route cache + owner shard |
| `rebalanceShard` | 管理性分片重平衡 | GM / scheduler，必须审计 |

候选非目标：

- 首版不承诺批量迁移。
- 首版不承诺无缝客户端同步。
- 首版不承诺强一致全局查询。
- 首版不允许业务直接修改非 owner shard 状态。

## 11. Actor 与线程归属候选

候选 Actor lane：

| Lane | Key | 适合状态 |
| --- | --- | --- |
| World lane | `worldId` | 世界元数据、分片列表、重平衡计划 |
| Shard lane | `worldId + shardId` | shard 内实体状态、局部广播、迁移接收 |
| Entity lane | `entityId` | 若后续需要独立实体 actor，可选 |
| Migration lane | `migrationId` | 复杂迁移协调，可选 |

硬规则候选：

- IO 线程不执行迁移状态修改。
- Actor 线程不执行不可控远程 IO。
- 跨 shard 修改必须通过消息或 RPC，不允许直接引用对象。
- `moveEntity` 必须在 owner shard lane 串行执行。
- `requestMigration` 必须先进入 source shard lane。
- `prepareMigration` 和 `commitMigration` 必须进入 target shard lane。
- 超时恢复可以使用后台或虚拟线程触发，但状态修改仍回到 Actor lane。

## 12. 跨服路由候选

后续正式实现可能需要：

| 能力 | 候选职责 |
| --- | --- |
| Route Resolver | 根据 `worldId` / `shardId` 找到目标节点 |
| Route Cache | 缓存分片路由和 epoch |
| Route Invalidator | 路由变更时失效旧缓存 |
| Remote Gateway | 通过 RPC 投递跨 JVM 命令 |
| Discovery Adapter | 从服务发现读取节点信息 |

候选规则：

- 路由缓存必须带 `routeEpoch`。
- 旧 `routeEpoch` 的迁移和写入请求必须可拒绝。
- 路由解析失败时，同步路径默认失败，不在 Actor 线程阻塞重试。
- 跨服 RPC 必须传递 `traceId`。
- 超时必须进入错误日志、性能日志和指标。

## 13. AOI 拼接和广播候选

开放世界常见的跨分片可见性问题：

- 实体在分片边界附近需要看到邻近分片实体。
- 实体迁移时客户端可能同时收到源 shard 和目标 shard 的广播。
- 广播乱序可能造成位置回退或重复出现。
- 跨 shard 查询可能产生旧快照。

最小候选规则：

- 首版可以只定义边界，不实现 AOI 拼接算法。
- 广播消息必须带 `stateVersion` 或 `eventSeq`。
- 客户端可按版本丢弃旧消息。
- 源 shard 在 `SOURCE_LOCKED` 后不得继续广播普通移动。
- 目标 shard 在 `TARGET_COMMITTED` 后才广播接管事件。
- 跨 shard 可见性应有带宽预算和最大邻接 shard 数限制。

## 14. 数据与缓存边界候选

| 数据 | 候选存储 | 说明 |
| --- | --- | --- |
| World metadata | PostgreSQL / MongoDB | 世界状态、分片策略、route epoch |
| Shard metadata | PostgreSQL / MongoDB | 分片状态、nodeId、capacityHint |
| Entity snapshot | MongoDB | 实体持久化快照 |
| Migration record | PostgreSQL / MongoDB | 迁移状态机和幂等记录 |
| Route cache | Redis / local cache | 分片路由和 epoch |
| Short state cache | Redis / local cache | 热实体短期快照，可降级 |

候选规则：

- 数据访问必须通过 Repository / DataService 抽象。
- Route cache 不得成为唯一事实来源。
- 迁移记录必须支持按 `migrationId` 幂等查询。
- Entity snapshot 必须有 `stateVersion`。
- 落库失败时必须保留现场，不能释放源 shard 后丢失状态。
- Redis key、TTL、版本号和降级策略属于高风险数据契约。

## 15. GM 与运维候选

可能的运维命令：

| 命令 | 风险 |
| --- | --- |
| 查询 world / shard 状态 | 低风险，但仍需权限 |
| 标记 shard draining | 影响路由和新进入 |
| 强制迁移实体 | 可能造成状态不一致 |
| 重放迁移恢复任务 | 可能重复接管或释放 |
| 修复 entity owner shard | 高风险数据修复 |
| 触发分片重平衡 | 影响大量实体 |

候选规则：

- 高风险命令必须支持 dry-run。
- execute 必须写审计日志。
- 审计失败应阻断高风险执行。
- 命令必须记录操作者、IP、traceId、目标 world、目标 shard 和影响实体范围。
- 不允许在本文中定义完整 GM API。

## 16. 日志候选

至少需要考虑：

- 运行日志：world / shard 启停、状态变化。
- 业务日志：进入世界、移动、迁移请求、查询。
- 审计日志：GM 强制迁移、draining、重平衡、修复归属。
- 错误日志：路由失败、迁移失败、状态版本冲突、落库失败。
- 性能日志：迁移耗时、RPC 耗时、广播扇出、快照大小。
- 安全日志：越权迁移、非法分片操作、重复执行高危命令。

候选字段：

| 字段 | 说明 |
| --- | --- |
| `traceId` | 全链路追踪 |
| `worldId` | 世界 ID |
| `shardId` | 当前 shard |
| `sourceShardId` | 源 shard |
| `targetShardId` | 目标 shard |
| `entityId` | 实体 ID，注意高基数使用限制 |
| `migrationId` | 迁移幂等 ID |
| `routeEpoch` | 路由版本 |
| `stateVersion` | 状态版本 |
| `errorCode` | 错误码 |
| `costMillis` | 耗时 |

注意：

- `entityId`、`migrationId` 不建议作为 Prometheus 高基数标签。
- 审计日志需要脱敏操作者上下文中的敏感字段。
- 错误日志必须绑定 ErrorCode。

## 17. 指标候选

| 指标 | 类型 | 标签候选 | 高基数限制 |
| --- | --- | --- | --- |
| `zero_world_entities` | gauge | `world`, `shard`, `entity_type` | 禁止 entityId |
| `zero_world_migration_total` | counter | `world`, `result`, `reason` | 禁止 migrationId |
| `zero_world_migration_inflight` | gauge | `world`, `shard` | 禁止 entityId |
| `zero_world_migration_latency_ms` | histogram | `world`, `result` | 禁止 source/target 组合过多 |
| `zero_world_route_resolve_total` | counter | `result`, `reason` | 禁止 nodeId |
| `zero_world_broadcast_fanout` | histogram | `world`, `message_type` | 禁止 entityId |
| `zero_world_handoff_bytes` | histogram | `world`, `entity_type` | 禁止 migrationId |

候选规则：

- 标签预算必须先确认。
- 不把玩家 ID、实体 ID、迁移 ID、traceId 作为指标标签。
- 迁移耗时必须区分成功、拒绝、失败、恢复完成。
- 广播扇出和 handoff size 是容量评估的首批证据。

## 18. ErrorCode 候选分类

本文不冻结 ErrorCode，只列候选分类：

| 分类 | 示例 |
| --- | --- |
| 协议错误 | 非法 worldId、非法 shardId、非法迁移参数 |
| 路由错误 | 分片不存在、路由过期、目标节点不可达 |
| 状态错误 | world closed、shard draining、entity not owner |
| 幂等错误 | migrationId 重复但参数不一致 |
| 并发错误 | source lock 失败、stateVersion 冲突 |
| 数据错误 | snapshot 保存失败、迁移记录写入失败 |
| RPC 错误 | prepare 超时、commit 超时、release 超时 |
| 权限错误 | GM 强制迁移权限不足 |
| 系统错误 | 未知恢复失败、状态不一致 |

## 19. Focused Tests 候选

正式实现前建议先确认测试名和验收口径：

| 测试名候选 | 验证点 |
| --- | --- |
| `enterWorldAssignsInitialShard` | 进入世界时分配初始 shard |
| `moveRequiresOwnerShard` | 非 owner shard 拒绝移动 |
| `migrationLocksSourceBeforeHandoff` | 源 shard 冻结后才发送 handoff |
| `targetRejectsStaleRouteEpoch` | 目标 shard 拒绝过期路由 |
| `migrationIsIdempotentByMigrationId` | migrationId 幂等 |
| `duplicateMigrationWithDifferentTargetRejected` | 重复迁移但目标不一致时拒绝 |
| `sourceRollbackKeepsEntityWhenPrepareFails` | prepare 失败后源 shard 保留实体 |
| `targetCommitPublishesNewOwnerOnce` | 目标接管事件只发布一次 |
| `oldMoveRejectedAfterSourceLocked` | 源 shard lock 后拒绝旧移动 |
| `queryReturnsRouteVersion` | 查询返回路由或状态版本 |
| `routeResolveTimeoutDoesNotBlockActorThread` | 路由超时不阻塞 Actor 线程 |
| `gmRebalanceDryRunDoesNotMoveEntity` | dry-run 不迁移实体 |
| `auditFailureBlocksForcedMigration` | 审计失败阻断强制迁移 |
| `migrationMetricsAvoidHighCardinality` | 指标不带高基数标签 |

## 20. 首批推进建议

推荐顺序：

```text
world-shard scaffold
  -> RunLocalScaffold
  -> 阅读生成项目的 BUSINESS_GUIDE.md / NEXT_STEPS.md
  -> docs/world-shard-minimum-contract.zh-CN.md
  -> 提交 GitHub Design Proposal
  -> 维护者评审 API / 线程 / RPC / 存储 / 日志字段
  -> focused tests
  -> 最小正式实现
```

若要缩小第一步，可只确认：

1. World / Shard / Entity 归属模型。
2. 单实体迁移状态机。
3. route epoch 和 migrationId 幂等。
4. 源 shard lock 与目标 shard commit 的 focused tests。

## 21. 高风险确认问题

进入正式实现前，至少需要用户确认：

- 是否允许新增 `zero-world` / `zero-shard` 候选模块？
- 是否允许新增 World / Shard / Migration 公共 API？
- 世界、分片、实体和区域模型如何定义？
- 是否首版只支持单实体迁移？
- 迁移状态机是否采用 source lock -> target commit -> source release？
- route epoch、migrationId、stateVersion 如何生成和持久化？
- 跨服路由使用 RPC discovery、Nacos metadata，还是独立 route service？
- 迁移中客户端广播和旧消息丢弃规则如何定义？
- AOI 拼接首版只定义边界，还是实现邻接 shard 查询？
- 落库失败、RPC 超时和恢复任务如何降级？
- GM 强制迁移、重平衡和修复归属是否进入首版？
- 指标标签预算和高基数禁止项如何定义？
- 本轮是否只允许设计文档和 focused tests，不进入正式实现？

## 22. 明确不证明

本文不证明：

- 开放世界 / 分片 API 已冻结。
- `zero-world` 或 `zero-shard` 已存在。
- 跨进程迁移已经可用。
- 可靠状态交接已经完成。
- AOI 拼接或跨服广播已经实现。
- 分布式一致性、容量、长稳或故障恢复已经验证。

本文只证明：开放世界 / 分片迁移正式化前，已经有一份可被 doctor、readiness、advisor、roadmap 和 implementation slice selector 发现的最小契约草案。
