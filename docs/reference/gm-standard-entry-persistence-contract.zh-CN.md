# GM 标准入口与持久化安全运营最小契约

状态：`minimum-slice-implemented / productionReady=false`

接入总览见[GM 指南](../gm-admin.zh-CN.md)。本页说明核心契约：`zero-gm-rest` 已有独立请求处理适配，`GmRpcTransportAdapter` 仍为传输端口；它们不等于完整 HTTP/Kafka 运营服务。审计查询、留存、幂等和 break-glass 的增量见[GM 运营迁移说明](../migrations/0.1.0-p0-4-gm-production-operations.zh-CN.md)。

## 已实现

- `GmOperationRequest`：传输无关的请求封装，承载只读上下文、命令、目标、审批引用和 dry-run 标志。
- `GmOperationEndpoint`：可被 REST、Kafka RPC、CLI 或内部适配器复用的统一入口；不创建网络端口，不绑定具体协议。
- `GmOperationResponse`：稳定 `code/message/result/traceId` 响应，并携带 `businessCommitState/auditFailed/idempotencyFailed` 基础设施事实。
- `GmAuditRecord` / `GmAuditRecordStore`：将已经安全化的事件转换为不可变白名单记录，并以完整 eventId/记录内容幂等追加；只有已接受或可证明的精确重复才算成功。
- `GmTransportMetadata` / `GmTransportAdapter`：为 HTTP/Kafka 等上层适配器定义可信 trace、correlation、idempotency、source IP 和认证 fail-closed 边界；核心不绑定具体传输。
- 入口先执行授权，再执行 dry-run/execute；拒绝不会调用业务 handler。

## 安全边界

- 认证、账号、JWT/SSO、RBAC/IP/审批持久化由上游应用提供。
- REST 和 Kafka RPC 适配器必须在自己的模块中实现，不应进入 `zero-gm` 核心。
- 可信来源 IP 必须由网关/传输层确定；可使用 `zero-security/TrustedProxyResolver` 从可信 peer 向左验证代理后缀，GM 核心不自行信任 forwarded headers。
- 审计 store 负责加密、保留周期、访问控制、重试和灾备；核心只定义同步失败语义。
- 不记录密码、token、raw command、原参数、完整 IP、原始身份、业务快照或异常原文。

## 2026-10-08 存储与授权要求

- 空访问策略默认拒绝；HIGH/CRITICAL 强制审批，CRITICAL 强制可信第二人验证。验证器故障同样拒绝，第二复核验证必须绑定解析后的完整请求。
- `GmAuditRecordStore.append` 的 ACCEPTED 必须表示已接受记录；DUPLICATE 仅在 `containsExact(record)` 证明同 eventId 的完整记录一致时成功，默认实现返回 false。REJECTED/null/冲突均为审计失败。eventId 和 operationId 改为完整随机 UUID；同事件重试须复用同一事件/记录，不重新生成 ID。
- 在途幂等记录不得按终态 TTL 回收。complete/fail 原子检查 key、fingerprint 和 IN_PROGRESS，终态保留期从完成时刻起算。后端写后失联仍可能抛错；不能由异常推断未提交。
- 持久化/多进程幂等后端必须显式注入 `GmOperationFingerprint.hmac(keyId, key)`，共享至少 32 字节密钥及版本。轮换会改变指纹，须协调仍存活记录；默认进程随机密钥不跨重启。
- 响应与 REST 保留业务提交状态及独立失败标记；IN_PROGRESS/FAILED 返回 UNKNOWN，complete 失败可同时为 COMMITTED 与 idempotencyFailed。COMMITTED/UNKNOWN 禁止自动重执行业务。
- `InMemoryGmAuditQueryStore` 持有构造时不可变快照，分页按 time/eventId 排序；筛选结果中不存在的游标返回 AUDIT_CURSOR_INVALID，调用者必须明确重新查询，不静默重启分页。

## 回归证据

`GmOperationEndpointTest`、`GmIdempotencyOutcomeTest`、`GmSafetyBoundaryTest` 和 `GmRestTransportAdapterTest` 覆盖授权、长耗时/重入、记账故障、可信复核绑定、HMAC、分页及 HTTP 事实保留。Java 21 扩展模块 quality 验证证据：`target/audit-20261008-b0-b2-quality-final.log`。真实审批/审计后端、跨进程恢复和密钥轮换尚未验收。
