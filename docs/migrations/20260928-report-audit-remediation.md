# 2026-09-28 报告核实与修复迁移说明

本次针对网络、外部系统、安全、数据缓存、Actor/runtime 和游戏域报告完成逐项核查。完整断言、源码位置、测试证据和剩余风险见任务矩阵 `tasks/active/report-audit-remediation/AUDIT-MATRIX.md`。

## 需要迁移的 0.x 行为

### 网络

- `ServerFactory.tcp(options, handler, executor)` 和不带 lifecycle 的 codec 重载现在 fail-fast。生产入口必须传入 `ProductionNetworkLifecycle`；确实自行实现握手/鉴权的低层协议改用明确命名的 `tcpUnmanaged(...)`。
- UDP 默认入口仍是无连接 datagram 语义。需要地址上下文、open/close 事件和会话预算时，使用带 `ConnectionListener` 与 `UdpSessionOptions` 的重载。UDP 不自动获得重传、拥塞控制或鉴权。
- `ServerFactory.kcpUnsupported(...)` 仍是能力边界，不得用于生产流量。

### 数据与缓存

- `RedisDataAdapter.registerZcodeRepository(...)` 不再静默创建本地 Map；它会显式失败。真实 Redis 使用 `registerDriverRepository(...)` 并注入 `RedisClient`，单进程原型使用 `registerInMemoryZcodeRepository(...)`。
- PostgreSQL/Mongo 条件写会返回更准确的 `DataErrorCode`。调用方应只对 `VERSION_CONFLICT`/`CONCURRENT_WRITE` 做业务重试，后端不可用和写失败应进入恢复或降级流程。
- Mongo `findAll()` 默认上限为 10,000，可通过构造器调整；超过上限返回 `READ_LIMIT_EXCEEDED`。大集合应改用分页或后端流式 API。
- `DefaultPersistenceManager.flushNow()` 每次调用获得独立完成信号；后续请求不再复用先到请求的 future。脏对象达到上限会拒绝新登记，必须监控统计并做降级。
- `LayeredCacheService` 的写回失败进入有界重试队列，可调用 `retryWriteBacks(...)`；L2 为空仍表示明确选择纯本地缓存，强一致对象应使用 Repository/事务。

### 运行时与游戏域

- `LocalPlayerService.login` 对同一账号的第二次登录返回 `ACCOUNT_ALREADY_ONLINE`，不再覆盖旧 session。顶号、踢线和分布式 lease 由业务装配。
- `LocalWorldService.migrateAsync(...)` 是推荐 API；同步 `migrate(...)` 保留为 deprecated blocking facade，不应在 Actor、Netty 或 IO 执行器中调用。
- `SyncEnvelope` 显式传入的 `payloadHash` 必须与规范化 payload 的 SHA-256 一致；错误摘要现在在构造时拒绝。
- 帧同步的 `MARK`、`REJECT`、`BUFFER` 和 `REPEAT_LAST` 仍是显式策略，不因报告而改变为单一业务规则。

## 不变但必须明确选择的能力

KCP、可靠 UDP、分布式 replay store、跨进程 world ownership 和可靠房间事件日志没有被伪造实现。Nacos/Kafka/Redis 的真实 adapter 已接入，但没有外部集群、认证、故障注入和容量证据时仍按 fail-closed 处理。生产项目必须在组合根选择这些实现并完成自身的安全、事务、补偿、监控和长稳验证。

## 回滚与兼容

本次属于 0.x 允许的必要破坏性修复。需要暂时保留旧 TCP 或本地 Redis 行为的项目应显式调用 `tcpUnmanaged` 或 `registerInMemoryZcodeRepository`，不要通过反射恢复旧默认路径。所有变更均可通过回退到迁移前版本回滚，但旧版本不再具备本次的 fail-fast、错误分类和边界保护。
