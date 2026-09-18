# GM 与后台接入

适用版本：`0.1.0-SNAPSHOT`，`productionReady=false`。框架提供 GM 命令、授权与审计边界，后台页面和真实运营服务由应用实现。

## 当前可用能力

| 模块 / 类型 | 已有行为 | 应用需要提供 |
| --- | --- | --- |
| `zero-gm` / `GmCommandDsl`、Registry、Executor | 路径与位置参数解析、最长路径匹配、dry-run/execute、错误与审计 hook | 业务命令定义和 handler |
| `GmOperationAuthorizer` | 权限、来源 IP/CIDR、审批事实与第二复核者校验；拒绝后不执行业务 | 可信身份、角色/权限策略和审批验证 provider |
| `GmOperationEndpoint` | 统一调用入口，返回 `code/message/result/traceId`；可注入幂等 store | 传输接线、可靠存储、业务副作用幂等 |
| `zero-gm-rest` / `GmRestTransportAdapter` | 处理 `POST /gm/operation`，限制 body，调用显式身份 provider 和 transport delegate | HTTP listener、真实认证、TLS、标准 JSON 边界及完整审计接线 |
| `GmRpcTransportAdapter` | RPC 请求/响应端口 | Kafka 消费/发布、路由、超时和副作用约束的具体实现 |
| 审计、幂等、break-glass | 查询分页、留存/归档、claim/冲突/TTL、一次性紧急授权契约和内存参考实现 | 跨进程持久化、访问控制、备份恢复与运营流程 |

REST 处理器与 RPC 端口不是已运行的网络服务。当前 REST 使用受限的平面字符串字段解析器，不能按通用 JSON 服务使用；其 HTTP 响应只包含 `code/message`，与核心 `GmOperationResponse` 的四字段结构不同。真实传输中的幂等键、审计和安全上下文需要贯通验证。

## 接线顺序

```text
应用 HTTP / RPC / CLI 入口
  -> 认证与可信来源
  -> GmTransportAdapter / GmOperationEndpoint
  -> 权限、IP、审批事实校验
  -> GmCommandExecutor dry-run / execute
  -> 业务 handler 与安全审计
```

业务状态修改通过所属 Actor、Repository / DataService 完成。IO 线程不执行阻塞业务。缺少身份 provider 的 REST `failClosed(...)` 入口拒绝请求；添加依赖不会自动生成角色、创建账号或启动服务。

详细接线契约见[授权与操作上下文](reference/gm-operation-context-contract.zh-CN.md)和[标准入口与审计存储](reference/gm-standard-entry-persistence-contract.zh-CN.md)。

## 命令与 dry-run

DSL 使用路径和位置参数，例如 `/mail send playerId itemId count`。首版支持双引号参数，不支持脚本、表达式、变量求值或批处理。公告、封禁、邮件、道具、订单补偿等属于应用业务命令，不是框架内置功能。

`dryRun` 由 handler 显式实现预演，不调用正式修改逻辑，也不得修改玩家、场景或持久化状态。完整审批服务仍由应用负责；`approvalRequired`、审批状态、审批引用和第二复核者表达上游作出的事实。

## 审计与副作用

`GmAuditHook` 在 DSL resolve、参数匹配、request 和安全 metadata 构造成功后记录执行前、执行后或失败事件。解析失败、未知命令、参数数量错误不自动产生 `GmAuditEvent`，传输层应接入安全失败审计；`GmSecurityFailure` 仅提供载体。

审计遵守以下边界：

- `GmAuditAttributionFactory` 生成 operator/source/approval/target 安全引用，禁止保存 raw command、参数值、token、完整 IP、原始身份、数据快照或异常原文。
- `GmAuditRecordFactory` 将安全事件转为 `ZeroLogRecord`，通过 `LogAppender` 安全管线写入。
- `PersistentGmAuditHook` 和 store 接口不自带数据库可靠性保证；内存实现不跨进程、不抗重启。
- 执行前审计失败阻止 handler；执行后审计失败不能回滚已发生的业务副作用。

`GmBusinessCommitState` 为 `NOT_APPLICABLE / NOT_COMMITTED / COMMITTED / UNKNOWN`。`COMMITTED` 与 `UNKNOWN` 禁止自动重试；应用仍需协调外部副作用。对直接注入 `GmIdempotencyStore` 的入口，重复键/请求指纹由 store 决策；真实 REST/Kafka 链路不能仅凭接口存在就宣称已实现端到端幂等。

## 验证与后续

从仓库根目录执行：

```bash
mvn -B -ntp -pl zero-gm,zero-gm-rest -am test
```

当前测试覆盖命令解析、授权拒绝、统一入口、审计分页、幂等与紧急授权的内存语义，以及 REST 请求处理边界。真实 listener、身份源、持久审批/审计、Kafka 重投和恢复仍需专项验证，见[路线图 P0-4](optimization-roadmap.zh-CN.md)。
