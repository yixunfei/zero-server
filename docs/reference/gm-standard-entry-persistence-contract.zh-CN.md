# GM 标准入口与持久化安全运营最小契约

状态：`minimum-slice-implemented / productionReady=false`

接入总览见[GM 指南](../gm-admin.zh-CN.md)。本页说明核心契约：`zero-gm-rest` 已有独立请求处理适配，`GmRpcTransportAdapter` 仍为传输端口；它们不等于完整 HTTP/Kafka 运营服务。审计查询、留存、幂等和 break-glass 的增量见[GM 运营迁移说明](../migrations/0.1.0-p0-4-gm-production-operations.zh-CN.md)。

## 已实现

- `GmOperationRequest`：传输无关的请求封装，承载只读上下文、命令、目标、审批引用和 dry-run 标志。
- `GmOperationEndpoint`：可被 REST、Kafka RPC、CLI 或内部适配器复用的统一入口；不创建网络端口，不绑定具体协议。
- `GmOperationResponse`：稳定 `code/message/result/traceId` 响应。
- `GmAuditRecord` / `GmAuditRecordStore`：将已经安全化的事件转换为不可变白名单记录，并以 eventId 幂等追加；重复写入返回 `DUPLICATE`。
- `GmTransportMetadata` / `GmTransportAdapter`：为 HTTP/Kafka 等上层适配器定义可信 trace、correlation、idempotency、source IP 和认证 fail-closed 边界；核心不绑定具体传输。
- 入口先执行授权，再执行 dry-run/execute；拒绝不会调用业务 handler。

## 安全边界

- 认证、账号、JWT/SSO、RBAC/IP/审批持久化由上游应用提供。
- REST 和 Kafka RPC 适配器必须在自己的模块中实现，不应进入 `zero-gm` 核心。
- 可信来源 IP 必须由网关/传输层确定；框架不解析 forwarded headers。
- 审计 store 负责加密、保留周期、访问控制、重试和灾备；核心只定义同步失败语义。
- 不记录密码、token、raw command、原参数、完整 IP、原始身份、业务快照或异常原文。

## 验证

`GmOperationEndpointTest` 覆盖统一成功响应、TraceId 保留、授权拒绝和 handler 不执行；`GmOperationAuthorizerTest` 覆盖 RBAC、CIDR、审批、双人复核和脱敏事件。
