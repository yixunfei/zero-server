# GM 与后台接入

## 2026-10-07 命令目标与审批绑定

`GmOperationEndpoint` 先解析一次命令，按 `targetParameterName` 找到实际实参，与请求 `target` 精确比较；不一致拒绝并记录脱敏 `TARGET_MISMATCH`。无固定目标参数的命令仍使用请求目标。授权和后续 handler 使用同一解析结果。

策略或指令定义任一 `approvalRequired=true` 或命令风险为 HIGH/CRITICAL 时，必须由 `GmApprovalVerifier` 验证实际目标和审批 token；默认 verifier 拒绝。CRITICAL 或策略要求双人复核时，另由 `GmSecondReviewerVerifier` 校验完整命令、全部参数、目标、操作者、令牌有效期和复核人权限。dry-run 和已完成幂等请求的结果重放也需要重新授权。裸授权及执行入口已收为包内，外部统一走 `GmOperationEndpoint`。验证器运行时异常按授权拒绝处理，保留原异常作为 suppressed。见[迁移说明](migrations/20261008-audit-b0-b2.zh-CN.md)。

## 2026-10-04 反馈专项边界

`GmOperationEndpoint` 先完成授权，再登记幂等 claim；指纹包含 `approvalToken`，授权失败不会创建或锁死幂等键。handler 的 `ZeroException`、运行时异常和 `Error` 均尝试终态记账；存储失败时可能保留 `IN_PROGRESS`，响应明确标记记账失败，Error 路径则保留原实例并附加次生异常。`IN_PROGRESS` 不按 TTL 回收，终态保留期从实际完成时刻计算；进程崩溃或未知记账结果必须由应用持久化实现核实恢复。

审批 token 目前仍只验证不核销，跨请求重放窗口需要由真实授权 provider 提供一次性消费或状态 fencing；0.x 接口变化见本次迁移说明。

2026-10-03：本地 `InMemoryGmIdempotencyStore` 最多保留 4096 条记录；仅终态按到期索引回收，在途记录不会因 TTL 被回收；满额时新键返回容量拒绝，已有键仍可查询去重结果。高容量及耐久需求应注入应用实现；详见[迁移说明](migrations/20261003-concurrency-async-performance.zh-CN.md)。

适用版本：`0.1.1`，`productionReady=false`。框架提供 GM 命令、授权与审计边界，后台页面和真实运营服务由应用实现。

## 当前可用能力

| 模块 / 类型 | 已有行为 | 应用需要提供 |
| --- | --- | --- |
| `zero-gm` / `GmCommandDsl`、Registry、Executor | 路径与位置参数解析、最长路径匹配、dry-run/execute、错误与审计 hook；字符上限 4096、token 上限 64 | 业务命令定义和 handler |
| `GmOperationAuthorizer` | 权限、来源 IP/CIDR、审批事实与第二复核者校验；拒绝后不执行业务 | 可信身份、角色/权限策略和审批验证 provider |
| `GmOperationEndpoint` | 统一调用入口，返回业务提交状态、审计失败和幂等记账失败信号；可注入幂等 store | 传输接线、可靠存储、业务副作用幂等 |
| `zero-gm-rest` / `GmRestTransportAdapter` | 处理 `POST /gm/operation`，限制 body，调用显式身份 provider 和 transport delegate | HTTP listener、真实认证、TLS、标准 JSON 边界及完整审计接线 |
| `GmRpcTransportAdapter` | RPC 请求/响应端口 | Kafka 消费/发布、路由、超时和副作用约束的具体实现 |
| 审计、幂等、break-glass | 查询分页、留存/归档、claim/冲突/TTL、一次性紧急授权契约和内存参考实现 | 跨进程持久化、访问控制、备份恢复与运营流程 |

REST 处理器与 RPC 端口不是已运行的网络服务。REST 使用严格 JSON 对象解析；HTTP 响应投影核心响应的 `code/message/traceId/correlationId`，并附带 `businessCommitState/auditFailed/idempotencyFailed` 及受白名单约束的结果。真实传输中的幂等键、审计和安全上下文仍需要应用贯通验证。

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

`dryRun` 由 handler 显式实现预演，不调用正式修改逻辑，也不得修改玩家、场景或持久化状态。完整审批服务仍由应用负责；审批状态、引用和请求声明的第二复核者只有经过可信 provider 验证才能构成授权事实。

## 审计与副作用

`GmAuditHook` 在 DSL resolve、参数匹配、request 和安全 metadata 构造成功后记录执行前、执行后或失败事件；统一端点也会为解析、授权、幂等拒绝和重放记录安全边界事件。不能证明审计记录已接受或是同一完整记录的精确重复时，操作按审计失败处理。

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

## REST 输入与响应（2026-10-08）

POST /gm/operation 接受严格 UTF-8 JSON 对象，默认最大 64 KiB。traceId、correlationId、idempotencyKey、command、target 是非空字符串；approvalToken、secondReviewer 是可选字符串，各字段最大 256 字符。dryRun 必须明确传 JSON boolean，例如 true；缺失、字符串、数字、null、未知键、重复键或尾随内容都在身份解析和业务调用前返回 400。

响应保留 code、message、traceId、correlationId、businessCommitState、auditFailed、idempotencyFailed；有执行结果时另含 result.commandKey、dryRun、success、errorCode、data。结果对象内部拒绝也会反映为失败 HTTP 状态。幂等命中仍返回当前请求的追踪字段。handler 自由消息与任意 data 默认不对外输出；四参 GmRestTransportAdapter 构造器可传 Set<String> 字段白名单（最多 32 键，单值最大 1024 字符），应用必须事先脱敏这些值。

| HTTP 状态 | 含义 |
| --- | --- |
| 400 | JSON/元数据/命令参数或协议错误 |
| 401 / 403 | 缺少可信身份 / 授权拒绝 |
| 404 / 405 | 路由或命令不存在 / 方法错误 |
| 409 | 业务拒绝，或幂等冲突、在途、历史失败 |
| 429 | 幂等容量背压，本次未执行业务 |
| 413 | 超过请求体预算 |
| 500 / 503 | 内部失败 / 远程服务、数据访问或缓存失败 |

REST 适配器仍不创建 listener、TLS、认证或授权 provider。未预期的 provider 异常向宿主传播，由宿主统一异常处理，不伪装成输入错误。解析失败的追踪字段为空；解析成功后的拒绝带当前请求的追踪标识。

## 幂等状态与重试（2026-10-07）

容量不足返回 `IDEMPOTENCY_CAPACITY_EXCEEDED`（REST 429），本次未占位或执行业务，可以在释放容量后重试。同键不同指纹、仍执行中、历史失败分别返回 `IDEMPOTENCY_CONFLICT`、`IDEMPOTENCY_IN_PROGRESS`、`IDEMPOTENCY_FAILED`（REST 409）；不得把这三种情况等同容量背压。

`approvalToken` 参与长度前缀 HMAC 指纹；本地便利入口使用随机 JVM 密钥，跨进程/持久化实现必须显式共享密钥和 key id。同键更换审批令牌被拒绝。FAILED 在配置 TTL 内保留，避免业务已提交但响应失败后自动重执行；必须先核实业务结果，TTL 到期也不代表可以忽略业务幂等。接口、迁移和回归见 [审计收尾](migrations/20261007-full-code-audit-remediation.zh-CN.md)。

在途和历史失败响应使用 `UNKNOWN`，因为原操作的副作用未获确认。成功提交后 complete 失败返回 `IDEMPOTENCY_STORE_FAILED`、`COMMITTED` 和 `idempotencyFailed=true`；执行后审计失败保留 `COMMITTED` 和 `auditFailed=true`。原业务拒绝码不会因次生审计/记账故障被覆盖；调用方必须联合读取错误码、提交状态和两个标记，不得仅凭 HTTP 500 自动重试。

## 安全上下文过期（2026-10-08）

`SecurityContext` 不再是 record，而是不可变 final class。创建时按验证时钟计算剩余寿命并建立本地 `System.nanoTime()` 上限；`expired(Instant)` 仍保留跨机绝对时间检查，但墙钟回拨不会让已耗尽的本地预算复活。KCP 等本地派生上下文必须调用 `withTransport` 共享同一到期状态。签名元数据验证以收到时间建立本地预算，签发时间晚于收到时间直接拒绝。该 0.x API 变化需要重新编译依赖。
