# zeroServer GM 操作上下文与审计契约草案

本文是 `gm-operation-context-contract` 的只读确认草案，用于在实现正式 GM REST/RPC 入口、RBAC、IP 白名单、审批流、安全日志和审计闭环前，把 GM 操作上下文、dry-run / execute、权限拒绝、审批阻断、审计事件、脱敏诊断和 focused tests 先收敛成可讨论输入。

状态：`draft / requiresConfirmation=true`

重要边界：

- 本文不是已经确认的正式 GM API、权限模型、审计 schema 或安全日志 schema。
- 本文不修改 `zero-gm`、`zero-log`、`zero-monitor`、`zero-rpc`、production starter 或任何业务运行时代码。
- 本文不新增 RBAC、IP 白名单、审批流、REST/RPC GM API、ErrorCode 枚举值、数据库表、Kafka topic 或 Prometheus 指标实现。
- 后续任何 GM API、权限系统、审批流、审计日志、安全日志、ErrorCode、线程模型或模块依赖方向变更，都应先通过 GitHub Design Proposal 说明威胁模型、权限边界、兼容性、审计与验证方案，并等待维护者评审。

## 1. 目标

GM 能力是长期运营入口，必须让后台操作在正式执行前具备一致的上下文和可追踪边界。第一阶段只确认最小契约口径：

- 谁在操作：操作者、角色、权限、来源、TraceId。
- 从哪里操作：REST、Kafka RPC、CLI/dev、内部任务或测试入口。
- 操作什么：命令、目标对象、目标范围、风险等级、参数摘要。
- 如何执行：dry-run、execute、审批前、审批后、拒绝、回滚候选。
- 如何阻断：权限拒绝、IP 拒绝、审批缺失、参数非法、审计失败。
- 如何留痕：审计日志、安全日志、错误日志、性能日志和指标候选。
- 如何保护：脱敏、最小字段、禁止输出敏感值、低基数指标标签。

该契约的价值是让后续 focused tests 和最小实现有清晰输入，而不是一次性完成完整后台系统。

## 2. 非目标

- 不实现完整 Web 后台。
- 不实现账号登录、密码、JWT、OAuth、SSO、组织架构或多租户模型。
- 不实现真实 RBAC 存储、IP 白名单存储、审批工单系统或通知系统。
- 不开放 REST 或 Kafka RPC GM 生产入口。
- 不连接 PostgreSQL、MongoDB、Redis、Kafka、Nacos 或外部审计系统。
- 不改变现有 GM DSL 和 `GmCommandExecutor` 的 prototype 定位。
- 不绕过 Actor lane、Repository / DataService 或业务侧线程安全入口。

## 3. 候选操作上下文

以下字段只是候选，不是正式 record、DTO 或 schema。

| 字段 | 候选含义 | 说明 |
| --- | --- | --- |
| `traceId` | 链路追踪 ID | 必须从入口传递到审计、日志、RPC、DB 和指标候选 |
| `requestId` | 单次 GM 请求 ID | 可用于幂等和排障，禁止作为默认指标标签 |
| `operatorId` | 操作者标识 | 需要脱敏策略，不等同于登录账号模型 |
| `operatorName` | 操作者展示名 | 默认不输出真实姓名，可输出脱敏摘要 |
| `operatorRoles` | 角色集合 | 候选为不可变、有序、可为空集合 |
| `operatorPermissions` | 权限集合 | 候选为字符串 permission 或结构化 resource/action |
| `sourceIp` | 来源 IP | 默认脱敏；完整 IP 只允许进入受控安全审计链路 |
| `entryType` | 入口类型 | `rest` / `kafka-rpc` / `cli` / `dev` / `internal` / `test` |
| `commandPath` | GM 命令路径 | 例如 `mail.send`、`player.ban` |
| `rawCommandHash` | 原始命令摘要 | 默认不记录完整 raw command，避免泄露参数 |
| `targetType` | 目标类型 | `player` / `account` / `server` / `scene` / `room` / `global` |
| `targetIdHash` | 目标标识摘要 | 高基数字段只进入日志，不进入默认指标标签 |
| `riskLevel` | 风险等级 | `low` / `medium` / `high` / `critical` 候选 |
| `dryRun` | 是否预演 | dry-run 不允许产生业务数据变更 |
| `approvalId` | 审批 ID | 可为空；不冻结审批系统实现 |
| `approvalState` | 审批状态 | 候选状态见下文 |
| `reason` | 操作原因 | 必须限制长度并脱敏 |

原则：

- 操作上下文应在入口创建，并在后续权限、审批、审计、执行和异常处理路径中只读传递。
- 上下文不能携带密码、token、完整连接串、完整请求体、大对象快照或敏感明文。
- 上下文进入业务执行前必须完成必要的权限、IP 和审批候选校验。
- 正式执行如需修改玩家、场景或核心状态，必须通过 Actor 消息、Repository / DataService 或业务侧确认的线程绑定入口。

## 4. 入口类型候选

| 入口 | 目标 | 候选约束 |
| --- | --- | --- |
| REST GM | 后台 Web 或运营平台 | 需要鉴权、RBAC、IP 白名单、审计和统一返回结构 |
| Kafka RPC GM | 跨服或平台服务调用 | 需要 `traceId`、`correlationId`、超时、幂等和审计 |
| CLI / dev GM | 本地开发和测试 | 默认只能用于 local/prototype，不等同于 production 入口 |
| internal task | 定时或系统触发 | 必须标明系统操作者和触发来源 |
| test fixture | 测试夹具 | 只能用于 focused tests，不进入 production starter |

确认问题：

- m1 首批是否同时覆盖 REST 和 Kafka RPC？
- dev / CLI GM 是否允许在 production profile 中存在？
- internal task 是否复用 GM 审计链路，还是使用单独系统审计事件？

## 5. dry-run / execute 候选语义

| 模式 | 数据变更 | 审计要求 | 失败策略 |
| --- | --- | --- | --- |
| `dry-run` | 禁止产生业务数据变更 | 必须记录预演开始、预演结果和失败原因 | 失败不进入 execute |
| `execute` | 可产生经授权的数据变更 | 必须记录执行前、执行后、结果和异常 | 审计前置失败候选为阻断 |
| `approval-preview` | 禁止产生业务数据变更 | 用于审批前展示影响范围 | 失败时不创建可执行审批 |
| `approved-execute` | 可产生已审批的数据变更 | 必须绑定审批 ID 与审批状态 | 审批无效时 fail-fast |

原则：

- dry-run 不得修改玩家、场景、房间、缓存、排行榜、库存、邮件、订单或持久化状态。
- execute 前必须确认上下文、权限、IP、审批和审计前置策略。
- 若审计前置失败，默认候选策略是阻断正式执行；是否允许降级执行必须单独确认。
- execute 后审计失败必须至少进入错误日志和安全日志候选路径，不能生吞异常。

## 6. RBAC 候选边界

首版可以在以下两个方向中二选一，正式实现前必须确认：

| 方案 | 说明 | 优点 | 风险 |
| --- | --- | --- | --- |
| 字符串 permission | 例如 `gm.mail.send`、`gm.player.ban` | 简单、便于脚手架和测试 | 数据范围和资源约束较弱 |
| 结构化 permission | `resource` + `action` + `scope` | 更适合长期后台 | API 和审计字段更复杂 |

候选规则：

- 权限拒绝必须绑定权限类 ErrorCode，并写安全日志。
- 权限检查必须发生在正式执行前。
- 高危命令即使拥有权限，也可以要求审批。
- 角色、权限和数据范围不得直接从客户端输入信任，应由可信入口或上游鉴权结果提供。

## 7. IP 白名单候选边界

IP 白名单只是辅助鉴权，不是独立权限。

候选方案：

- 仅定义 SPI，具体来源由项目接入。
- 提供内存白名单实现用于测试。
- CIDR 匹配作为候选能力，是否进入首版需确认。

候选规则：

- IP 拒绝必须早于业务执行。
- IP 拒绝必须写安全日志和审计拒绝事件。
- 日志默认输出脱敏 IP，例如保留网段摘要或哈希。
- 反向代理、网关和 `X-Forwarded-For` 的信任边界必须由 production profile 明确。

## 8. 审批状态候选

以下状态只是候选，不冻结正式枚举：

| 状态 | 含义 | 可执行 |
| --- | --- | --- |
| `NONE` | 不需要审批 | 是 |
| `PENDING` | 等待审批 | 否 |
| `APPROVED` | 已批准且未过期 | 是 |
| `REJECTED` | 已拒绝 | 否 |
| `EXPIRED` | 已过期 | 否 |
| `CANCELED` | 已取消 | 否 |

候选规则：

- 高风险命令默认要求审批，风险等级阈值待确认。
- 审批 ID 只能作为引用，不应承载审批详情大对象。
- 审批状态失效时必须 fail-fast，不能继续 execute。
- 审批人、申请人和执行人是否允许相同，需要单独确认。

## 9. 审计事件候选

审计事件应覆盖完整操作链路，但字段必须最小化。

| 事件 | 触发时机 | 说明 |
| --- | --- | --- |
| `gm.request.received` | 入口接收 | 建立上下文和 trace |
| `gm.auth.rejected` | 鉴权失败 | 未进入权限判断 |
| `gm.ip.rejected` | IP 白名单拒绝 | 不进入业务执行 |
| `gm.permission.rejected` | 权限拒绝 | 绑定权限类 ErrorCode |
| `gm.approval.required` | 需要审批 | 不执行正式 handler |
| `gm.approval.rejected` | 审批拒绝或失效 | fail-fast |
| `gm.dryrun.started` | dry-run 前 | 不允许数据变更 |
| `gm.dryrun.finished` | dry-run 后 | 记录预演结果摘要 |
| `gm.execute.started` | execute 前 | 审计前置成功后 |
| `gm.execute.finished` | execute 后 | 记录结果摘要 |
| `gm.execute.failed` | execute 异常 | 绑定 ErrorCode 和异常摘要 |

审计日志候选字段应复用 `docs/observability-minimum-field-contract.zh-CN.md` 的基础字段，并补充 GM 维度字段：

- `gmOperationId`
- `operatorIdHash`
- `entryType`
- `commandPath`
- `targetType`
- `targetIdHash`
- `riskLevel`
- `dryRun`
- `approvalId`
- `approvalState`
- `decision`
- `errorCode`

## 10. 安全日志候选

安全日志关注拒绝、越权、异常来源和策略命中，不应记录大业务对象。

| 场景 | 候选字段 |
| --- | --- |
| 鉴权失败 | `traceId`、`entryType`、`sourceIpMasked`、`reason`、`errorCode` |
| IP 拒绝 | `traceId`、`sourceIpMasked`、`whitelistPolicyId`、`errorCode` |
| 权限拒绝 | `traceId`、`operatorIdHash`、`commandPath`、`requiredPermission`、`errorCode` |
| 审批拒绝 | `traceId`、`approvalId`、`approvalState`、`commandPath`、`errorCode` |
| 审计失败 | `traceId`、`phase`、`sinkType`、`errorCode` |

禁止输出：

- 密码、token、密钥、完整连接串。
- 完整手机号、邮箱、身份证、真实姓名。
- 完整请求体、完整 raw command、完整玩家背包或完整场景快照。
- 未脱敏 IP、未经确认的账号明文和审批备注明文。

## 11. ErrorCode 分类候选

本文只定义候选分类，不定义正式枚举值。

| 分类 | 场景 |
| --- | --- |
| 权限错误 | 鉴权失败、权限不足、角色无效 |
| 安全错误 | IP 拒绝、来源不可信、策略命中 |
| 审批错误 | 未审批、审批拒绝、审批过期、审批状态不一致 |
| 协议错误 | REST/RPC 请求格式错误、参数非法 |
| 系统错误 | 审计前置失败、执行器异常、内部状态异常 |
| 数据访问错误 | 执行 GM 时读取或写入 Repository / DataService 失败 |

确认问题：

- 是否新增独立 GM ErrorCode 段？
- 权限错误与安全错误是否拆分？
- 审计失败是否使用系统错误还是审计专用错误？

## 12. 指标候选

指标只使用低基数标签：

| 指标 | 类型 | 标签候选 |
| --- | --- | --- |
| `zero_gm_requests_total` | counter | `entryType`、`commandGroup`、`result` |
| `zero_gm_rejections_total` | counter | `entryType`、`reason` |
| `zero_gm_audit_failures_total` | counter | `phase`、`sinkType` |
| `zero_gm_execution_seconds` | histogram | `entryType`、`commandGroup`、`dryRun` |

禁止作为默认指标标签：

- `operatorId`
- `sourceIp`
- `targetId`
- `traceId`
- `requestId`
- `approvalId`
- 原始命令或参数值

## 13. 线程与数据边界

- GM REST/RPC 入口不得在网络 IO 线程执行阻塞业务逻辑。
- GM 远程鉴权、审批查询、审计落地和数据访问必须进入框架统一管理的执行域。
- dry-run 只能调用安全查询或预演逻辑，不允许修改核心业务状态。
- execute 修改玩家在线数据时必须进入 player actor。
- execute 修改场景状态时必须进入 scene actor。
- 跨 Actor 修改必须走消息，不允许直接引用修改。
- 数据访问必须通过 Repository / DataService 抽象。
- 审计 hook 不允许生吞异常；失败策略必须由框架统一处理。

## 14. focused tests 候选

后续若用户确认进入最小实现，优先补以下 focused tests：

| 测试 | 验收点 |
| --- | --- |
| `dryRunDoesNotMutateState` | dry-run 不调用正式修改 handler，不产生数据变更 |
| `executeRequiresAuditBeforeMutation` | 审计前置失败时不调用业务 handler |
| `permissionDeniedWritesSecurityLog` | 权限拒绝绑定 ErrorCode 并写安全日志候选事件 |
| `ipRejectedBeforePermissionAndExecute` | IP 拒绝早于权限和执行 |
| `approvalRequiredBlocksExecute` | 审批缺失时 fail-fast |
| `approvedExecuteCarriesApprovalContext` | execute 审计事件包含审批引用 |
| `sensitiveFieldsAreRedacted` | token、密码、完整 IP、raw command 不进入默认日志 |
| `gmContextPropagatesTraceId` | TraceId 贯穿审计、安全日志和执行结果 |

这些测试在正式实现前仍需要用户确认 API、字段、ErrorCode 和线程边界。

## 15. 本地验证边界

- 本草案不需要 Docker。
- 不写入任何账号、密码、token、端口、连接串或本地环境变量。
- 如后续需要 PostgreSQL、Redis、Kafka 或外部审计 sink，应使用与个人数据隔离的本地或 CI 测试环境，并记录组件版本、配置来源、数据目录和清理方式。
- 本地凭据只能放入 `.gitignore` 排除的 `config/local-dev/*.local.md` 或 `config/local-dev/*.env`，不得进入正式文档、源码或提交记录。

## 16. 高风险确认问题

正式实现前至少需要确认：

- GM 首批入口是 REST、Kafka RPC，还是二者都需要？
- RBAC 首版使用字符串 permission，还是结构化 `resource/action/scope`？
- IP 白名单是否只定义 SPI，是否包含内存实现和 CIDR 匹配？
- 高风险命令的风险等级阈值是什么？
- 审批状态首版是否只保留 `NONE`、`PENDING`、`APPROVED`、`REJECTED`、`EXPIRED`？
- 审计前置失败是否必须阻断 execute？
- execute 后审计失败如何降级、告警和保留现场？
- 安全日志与审计日志的字段、保留周期、脱敏策略如何确认？
- 是否允许 dev / CLI GM 出现在 production profile？
- 是否需要更新 `docs/module-map.md` 并新增正式模块或依赖方向？

## 17. 后续推进顺序

推荐顺序：

```text
gm-operation-context-contract
  -> focused tests for context / dry-run / audit / rejection
  -> minimal in-memory policy SPI
  -> GitHub Design Proposal for RBAC / IP / approval runtime
  -> reviewed REST/RPC GM entry
  -> reviewed external audit sink / dashboard / alert
```

每一步只要涉及公共 API、权限、审批、日志字段、ErrorCode、线程模型或模块依赖方向，都应先提交 GitHub Design Proposal，并在维护者评审通过后实现。
