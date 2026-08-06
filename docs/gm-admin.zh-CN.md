# GM 与后台 API 设计

生产级 GM 安全运营的独立确认输入见 [GM 操作上下文与审计契约草案](gm-operation-context-contract.zh-CN.md)。该草案只用于讨论 GM 操作上下文、dry-run / execute、权限拒绝、审批阻断、审计事件和安全日志，不代表已经实现 RBAC、IP 白名单、审批流、REST/RPC GM 入口或正式日志 schema。

## 1. 目标

zeroServer 不内置完整 Web 后台项目。标准 API、安全鉴权、数据修改、上报、功能开启和逻辑调用是长期产品目标；当前 `zero-gm` 只提供命令 DSL、handler 编排和 O1 最小安全审计底座，不能据此宣称完整后台、RBAC、IP 白名单或审批引擎已经实现。

后台 Web 项目可以基于这些 API 自由实现。

## 2. 通信方式

默认：

- REST。

同时支持：

- 基于 Kafka 的 RPC。
- 第三方 SDK 或渠道请求适配。

## 3. 统一响应结构

REST API 使用统一返回格式：

```json
{
  "code": "ZERO-OK",
  "msg": "success",
  "data": {},
  "traceId": "trace-id"
}
```

## 4. 权限

权限模型：

- RBAC。
- IP 白名单。

IP 白名单只是辅助鉴权，不作为单独权限。只有内网或指定 IP 通过指定账户密码获取特定角色权限后，才能执行对应操作。

JWT 暂不作为当前服务器框架核心能力，多项目上级业务中台可自行接入。

## 5. GM 功能范围

需要支持：

- 公告。
- 服务器状态维护。
- 玩家查询。
- 封禁。
- 邮件。
- 道具发放。
- 充值订单失败补充。
- 在线人数。
- 日志检索。
- 配置发布。
- 热更发布。
- GM 指令。
- 运营活动开启。
- 兑换码/活动运营码生成。

## 6. 审批与 dry-run

数据修改需要支持：

- dry-run。
- 审批流。

dry-run 要求业务接口显式实现预演逻辑。

完整审批流属于后续高风险运营能力。当前仅有 `approvalRequired`、受控 `GmApprovalState` 和可选安全 `approvalRef` 归因，用于表达已由上游作出的审批事实；框架尚未提供审批引擎或审批状态机。

## 7. 审计日志

“后台操作全部记录审计日志”是完整运营平台的长期硬要求。当前 O1 只覆盖成功完成 DSL resolve、参数匹配、request 与安全 metadata 构造后的 handler 前后事件；DSL 非法、未知命令和参数数量不匹配发生在 metadata 构造前，当前不会生成 `GmAuditEvent`。

长期审计模型至少需要以下语义，但身份、地址、目标、审批、参数与数据差异必须保存为可归因安全引用、受控状态或不含原值的摘要，不能直接落原值：

- 操作者安全引用。
- 来源地址安全引用。
- 授权与权限决策摘要。
- 操作类型。
- 目标类型与安全引用。
- 参数名白名单、数量和可选 HMAC 请求指纹，不含参数值。
- 不含原值的数据差异摘要。
- dry-run 结果。
- 受控审批状态与可选安全引用。
- 执行结果。
- 真实失败 `ErrorCode` 与四态业务提交状态。
- traceId。
- 时间。

当前事件和统一日志禁止保存原 `GmCommandContext`、raw command、参数值、原 operator/source address/approvalId/target、roles、permissions、attributes、前后原值或原异常 message。

## 8. GM 指令 DSL

GM 指令需要命令 DSL。

示例：

```text
/mail send playerId itemId count
/player ban playerId reason duration
/item add playerId itemId count
```

GM 指令不需要强制走事件总线。

### 8.1 最小命令边界

`zero-gm` 当前已提供第一版最小可验证 GM command DSL 与执行编排，定位是“核心框架命令模型”，不是完整 Web 后台：

- `GmCommandDsl` 解析 `/mail send playerId itemId count` 形式的文本命令。
- DSL 首版只支持路径和位置参数，支持双引号参数，不支持脚本、表达式、变量求值或批处理。
- `GmCommandDefinition` 描述命令路径、参数名、风险等级、目标参数和是否要求审批。
- `GmCommandRegistry` 使用最长路径优先解析命令，例如 `mail send` 会优先匹配 `/mail send ...`。
- `GmCommandExecutor` 明确区分 `dryRun` 与 `execute`，dry-run 不会调用正式修改 handler。
- `GmAuditHook` 是审计入口；DSL resolve、参数匹配、request 与安全 metadata 构造成功后，执行器才在 dry-run 或正式 handler 调用前置、后置或失败阶段同步触发审计事件。解析失败、未知命令和参数数量错误当前不产生 `GmAuditEvent`。
- 审计 hook 失败会使当前命令失败；正式执行前审计失败时不会调用业务 handler。

### 8.2 handler 约束

GM handler 必须遵守线程与数据边界：

- dry-run 只能做预演和可安全查询，不允许修改玩家、场景或持久化状态。
- 正式执行如需修改玩家、场景或核心状态，必须通过 Actor 消息、Repository / DataService 或业务侧确认的线程绑定入口。
- handler 不允许吞异常；执行失败必须向上抛出，由上层统一异常处理、日志和审计链路记录。
- 第一版不内置 RBAC、IP 白名单、复杂审批流、REST 入口或 RPC 入口；扩展这些生产能力前应提交独立 Design Proposal，说明威胁模型、权限、审计和回滚边界。

### 8.3 审计字段

`GmAuditRecordFactory` 把已经安全化的 `GmAuditEvent` 转换为统一 `ZeroLogRecord`，再由 `LogAppender` 通过不可绕过的日志安全管线落地。旧的平行 `AuditLogRecord` 已删除。

当前事件与统一日志表达：

- `time`
- `phase`
- `traceId`
- `commandKey`
- `parameterNames` 与 `parameterCount`，不含参数值
- `dryRun`
- `result`；`FAILURE / REJECTED` 时绑定真实 `ErrorCode`，`STARTED / SUCCESS` 时禁止携带 `ErrorCode`
- `businessCommitState`
- 不含 handler 返回值或异常 message 的 `safeMessage`
- 不含参数值的 `structureFingerprint`
- 配置 HMAC 密钥时的可选 `requestFingerprint`
- `operatorRef` 与 `sourceAddressRef`
- `approvalRequired`、`approvalState` 与可选 `approvalRef`
- `targetType` 与可选 `targetRef`

`GmBusinessCommitState` 固定为 `NOT_APPLICABLE / NOT_COMMITTED / COMMITTED / UNKNOWN`。`COMMITTED` 与 `UNKNOWN` 均禁止自动重试；业务仍须保证幂等和外部副作用协调。当前审批字段只记录安全归因，不冻结或实现复杂审批流模型。
