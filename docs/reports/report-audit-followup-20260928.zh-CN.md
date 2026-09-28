# 九项问题复核与风险评估（2026-09-28）

核查基线：备份提交 `0a00eeb`，包含修改前全部工作树改动。分支 `zero/report-audit-remediation`。本报告区分可复现错误、既有正确保护和本地组件的设计边界；不将进程内测试解释为分布式一致性证明。

## 真实性与处置

| 项 | 判断 | 源码证据与处置 | 回归证据 |
| --- | --- | --- | --- |
| 1 房间事件后回滚 | 真实 | LocalRoomService 的消费者异常原先进入 apply 的恢复分支。现在区分提交后的事件交付失败，保留成员、状态、序号、历史和统计，仍传播带 cause 的异常。 | RoomCommitBoundaryTest：加入、开局、结算的下游先接收后抛错；幂等重试不重复通知。 |
| 2 flush 清除新标脏 | 当前基线不成立 | 每次 markDirty 分配新 generation，remove(key, entry) 是条件相等删除；即使 supplier 相同，generation 也使新旧 DirtyEntry 不相等。这不是单纯按对象引用删除。本次不改持久化算法。 | 已有 PersistenceReportAuditTest 的 sameSupplierDirtyMarkSurvivesOldFlush、aNewDirtyMarkSurvivesAnOlderFlush 重新通过。 |
| 3 L2 失败仍填 L1 | 真实 | 同步异常和异步失败都可能回填正/负缓存。现在默认不回填；LayeredCacheService 及 Redis 门面均提供显式本地降级选项。 | LayeredCacheServiceTest：同步/异步、正/负结果；RedisDistributedCacheServiceTest：默认与显式降级。 |
| 4 未知玩家自动建档 | 真实 | 原缺省加载会创建档案，登录未查仓库。现在默认 REJECT；CREATE_DEFAULT 显式可选，登录建立会话前查权威仓库。 | MissingPlayerPolicyTest：默认拒绝、缓存不能代替登录校验、已有玩家、显式创建、并发创建冲突。 |
| 5 跨 lane 迁移交接 | 部分真实 | 原全局 stateLock 已阻止 map 并发损坏，不能据此断言已存在两个独立分片副本。实际缺口是不同 lane 与目标提交过早清除令牌。现统一 entity lane，保留令牌到释放并核验 owner/token/epoch/version。 | WorldHandoffSerialTest：乱序拒绝、提交与释放之间排他、旧释放不干扰新迁移、真实执行器异步链与 lane 记录。 |
| 6 包装器丢 cause | 真实 | ScopedEnvelopeStore 原先用 null 重建异常。现在保留原异常作 cause，既有 DataErrorCode 和安全外层文案不丢失。 | StoreFailureBoundaryTest：读、写、删、计数的原始异常和错误码。 |
| 7 直接保存倒退 / 默认 CAS | 真实 | PostgreSQL upsert 加版本谓词，Mongo replace 加版本谓词并识别唯一键冲突；低版本拒绝。默认 saveIfVersion 直接报 ATOMIC_WRITE_UNSUPPORTED，要求自定义存储实现原子写。 | PG/Mongo VersionBoundaryExternalIT：真实数据库低/等/高版本、独立连接/客户端创建及更新 CAS 单一获胜；StoreFailureBoundaryTest：默认 CAS 不读写后端。 |
| 8 排行榜锁内回调 / 不回滚 | 部分真实 | 锁内回调真实；回调失败后保留提交是既有明确契约，不应回滚已经通知的外部副作用。提交、赛季转换和结算的回调均移到状态锁外，保留原异常传播。 | RankingCallbackLockTest：三类阻塞回调期间其他线程可读写；RankingNotificationContractTest：原异常、已提交状态和重入。 |
| 9 历史仅 1024 条 | 设计边界，缺口识别需增强 | 既有 droppedEventCount 已计数；淘汰不影响实时消费者收到事件。默认容量保留，新增容量配置及 eventHistory 原子窗口/gap 元数据。没有承诺持久可靠重放。 | RoomCommitBoundaryTest：容量 2 下实时 3 条、历史 2 条、gap 边界与不可变列表。 |

## 风险与业务选择

| 范围 | 原始风险 | 修复后的边界与缓解 |
| --- | --- | --- |
| 房间、排行榜通知 | 下游与内存分叉；锁被慢回调占用 | 房间状态不再回退；排行榜移出锁。通知失败仍可能部分交付，调用方需查询状态与业务幂等。可靠交付需持久状态/outbox 扩展；没有默认接入数据库或 MQ。 |
| 世界迁移 | 上次交接未释放就允许移动或再迁移 | 实体排他期延长到源释放，吞吐受同实体队列和既有全局锁约束。仅证明单进程；跨进程仍需租约、持久 fencing 和恢复流程。 |
| 缓存 | 错误负缓存屏蔽后来 L2 更新 | 默认重新访问 L2，故障期回源压力可能上升；需限流、singleflight 和容量预算。显式降级换可用性，需接受过期值/负值窗口；写回积压仍有界且非持久。 |
| 玩家 | 错误或猜测 UID 留下正式档案 | 默认拒绝且登录查库；新部署需明确注册流程。原型可选 CREATE_DEFAULT。身份认证、账号授权、删档后的缓存失效均由业务负责。 |
| 存储 | 旧信封覆盖新版本；伪 CAS 导致丢更新 | 自定义 SPI 未覆盖原子 CAS 将立即失败，是明确的 0.x 行为变化。PG/Mongo 直接 save 仍允许等版本替换，不能替代 CAS；恢复旧业务内容应写新版本。 |
| 异常诊断 | 根因丢失，排障困难 | 保留 cause，外层消息稳定。内部异常可能含后端细节，业务对外错误序列化需保持脱敏边界。 |
| 房间历史 | 用有界历史误作可靠日志 | 容量可配、缺口可查询；容量越大内存和已有 checkpoint 复制成本越高。默认 1024，不做无界保留；进程重启后历史丢失。 |

本次不将报错转为伪成功，不用补偿性回滚制造第二次状态分叉，也不强制所有业务接入同一基础设施。核心仍不依赖数据库、Redis 或 MQ。原型模板显式选择原型能力，正式业务选择 Repository/Cache/事件端口。

## 验证记录

- Java 21，Maven 3.9.8。受影响模块及依赖的 focused test 已通过。
- PostgreSQL / MongoDB 外部验证已通过，四个 ExternalIT 均无跳过，覆盖原有 CRUD 和新增版本/并发 CAS。
- 根 reactor 的 57 个模块全部通过：870 项单元测试、1 项内部集成测试，失败、错误、跳过均为 0；按仓库配置执行 Checkstyle、PMD、SpotBugs、JaCoCo 门禁。
- 独立 examples/rpg-minimal 的 2 项测试通过；新生成 local 项目的 inspector、clean test 和 exec:java 均通过，返回 local-game=ok。
- 全量运行发现两个历史 JaCoCo 数据不匹配告警；只清除 zero-codegen / zero-world 的生成 coverage 数据后重新执行这两个模块的测试与质量门禁，通过且不再有该告警。
- 统计仅取本次运行日志，不混入 target 目录中的历史 XML。完整日志保存在本地归档 tasks/archive/20260928-report-audit-followup/logs。
- 外部依赖复用本项目隔离 Docker 环境：PostgreSQL 16 / MongoDB 7.0，端口分别为 127.0.0.1:15432 / 27018。实际 bind mounts 和 Docker Desktop 虚拟磁盘均位于 M 盘的 docker_space；凭据不进入代码或报告。测试结束已恢复这两个容器的原始停止状态，保留数据与凭据。
- 未证明：数据库 HA / failover、跨实例 World ownership、持久房间重放、通知 exactly-once、业务压测和长稳 SLA。本次未改 Redis 数据脚本，Redis 适配开关通过单元测试验证。

迁移方法见 [0.x 迁移说明](../migrations/20260928-report-audit-followup.md)。任务私有证据在本地 tasks 档案，公开证据以本报告和已跟踪测试为准。

## 合并前复验（2026-09-28）

- 恢复 LocalWorldService 类注释中的既有能力台账证据标记；未改变运行时行为。
- Java 21 下重新执行 `mvn -B -ntp -Pquality,integration-tests verify`：57 模块全部成功，871 项测试（870 单元测试和 1 项内部集成测试），失败、错误、跳过均为 0。完成时间为 2026-09-28 12:29:14（UTC+08:00）。
- `ZeroArchitectureGuard`、`ZeroFrameworkGapLedger`、`ZeroReleaseHardeningReadiness` 和 `git diff --check` 均通过。
- 本轮没有重跑外部数据库测试，外部验证证据沿用本报告前述记录。全量复验日志保存在本地 `target/merge-main-verification.log`。
