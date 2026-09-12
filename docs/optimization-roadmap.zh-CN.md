# zeroServer 后续优化路线图（接手指南）

状态：`roadmap / not-a-completion-claim / productionReady=false`

更新日期：2026-09-12

本文是面向后续接手者（人或 AI Agent）的差距分析与推进指南。它把当前系统的能力盘点为
“已具备 / 待补充 / 待完善 / 待优化”，并给出优先级、验收标准和标准工作流程。

重要边界：

- 本文不是完成声明。当前权威状态仍是 `productionReady=false`、`goalAchieved=false`
  （见 `docs/capability-matrix.zh-CN.md`、`docs/framework-gap-ledger.zh-CN.md`、
  `scripts/ZeroGoalCompletionAudit.java`）。
- 本文描述的能力边界来自当前工作树的源码、文档与脚本；接手时必须重新执行第 9 节的验证
  命令，不得把历史日志当作当前证据。
- 本文不包含游戏业务逻辑；所有条目都是通用框架能力。

---

## 1. 当前定位

一句话结论：

> 本地开发与原型验证闭环较完整；生产运行时处于 minimum-slice；部分玩法模块是 prototype；
> 容量、长稳、灾备和安全运营仍是 not-proven。

已形成的基础（不要重复建设）：

| 领域 | 已具备 | 关键路径 |
| --- | --- | --- |
| 模块化边界 | core 低依赖、runtime 显式装配、Adapter 分离 | `docs/module-map.md`、`scripts/ZeroArchitectureGuard.java` |
| Runtime 装配 | catalog/provider/selection、依赖闭包、资源账本、失败回滚、startup health | `zero-runtime`、`docs/modular-runtime-assembly.zh-CN.md` |
| 本地闭环 | Local Starter、脚手架、生成工程验证、Stage 0 quick/full | `zero-server-starter`、`scripts/ZeroStage0Acceptance.java` |
| 协议与代码生成 | `.si` → DTO/Codec/EventBO/Dispatcher、多语言客户端 | `zero-codegen`、`docs/protocol-dsl.zh-CN.md` |
| 数据与缓存 | 中立 Repository 工厂、角色绑定、L1/L2 缓存 | `zero-data`、`zero-cache` |
| RPC 与发现 | request/response/oneway/broadcast、Kafka Adapter、Nacos Adapter | `zero-rpc`、`zero-rpc-kafka`、`zero-discovery-nacos` |
| 可观测性底座 | 结构化日志、敏感字段安全门、低基数指标、Prometheus 文本 | `zero-log`、`zero-monitor` |
| GM 安全底座 | 命令 DSL、dry-run、四态提交审计、授权窄切片、标准入口、审计记录/存储契约 | `zero-gm`、`docs/gm-operation-context-contract.zh-CN.md` |
| 治理与验收 | 架构守卫、gap ledger、goal audit、发布检查单模板、迁移模板 | `scripts/`、`docs/release-checklist.zh-CN.md` |

---

## 2. 接手者必读规则

1. 每个优化项按“最小切片”推进：一次只做一个独立能力，先建档再实现。
2. 非平凡任务必须创建 `tasks/active/<task-name>/REQUIREMENTS.md`、`PLAN.md`、`RISK.md`、
   `VERIFY.md`（见根目录 `.codex/AGENTS.md` 与 `AGENTS.md` 第 5 节）。
3. 涉及公共 API、SPI、协议、线程模型、存储格式、权限、热更或模块依赖方向的变更属于高风险，
   必须先暂停并列出风险，等待确认后再实现。
4. 完成任何切片后必须：
   - 运行第 9 节对应验证命令；
   - 更新 `docs/framework-gap-ledger.zh-CN.md` 对应的 `scripts/ZeroFrameworkGapLedger.java`
     证据条目与 `scripts/ZeroGoalCompletionAudit.java`；
   - 更新本文档对应条目的状态；
   - 在 `CHANGELOG.md` 记录用户可见变更。
5. 禁止夸大：`focused tests 通过`、`Stage 0 通过`、`单节点故障恢复通过` 都不能写成
   productionReady；`productionReady=false` 只能在证据完整覆盖生产要求后调整。
6. 不执行未经确认的 commit、push、tag、发布或破坏性数据迁移。

---

## 3. P0：必须优先补齐

### P0-1 开箱即用体验与环境入口

进展（2026-09-12）：`partial`。已新增 Maven Wrapper（固定 Maven 3.9.8）、Java 21 toolchain 声明、`scripts/zero.ps1`、`scripts/zero.sh` 与 `ZeroUnifiedEntryVerifier --full-smoke`；Windows 入口真实链路、Stage 0 full（18/18）、quality、local integration 均通过。CI 已加入 Ubuntu/macOS/Windows 三平台真实 smoke 矩阵，但当前主机无法触发 hosted runner，Linux/macOS 实际 artifact 仍待 CI 执行，因此本条验收不能标记完成。

现状：用户需要自行处理 JDK 21、Maven、SNAPSHOT 安装顺序、examples 依赖根 install、local/production 区别；`ZeroLocalDoctor` 只诊断不修复；当前环境默认 Java 17 会被硬阻断。

待办：

- [x] 提供统一入口脚本 `scripts/zero.ps1` 与 `scripts/zero.sh`（或等价 CLI），覆盖：
  `doctor / init / generate / test / diagnose / run / stop`。
- [x] 环境不满足时输出明确修复建议（JDK 21 下载/切换、Maven 3.9+），不输出编译噪声。
- [x] 自动判断是否需要先 `mvn -DskipTests install`，避免 examples 因缺 SNAPSHOT 失败。
- [x] 为生成工程提供 `diagnose -> run -> stop` 的一键路径。
- [x] Maven Wrapper（`mvnw`）与固定 toolchain，统一本地与 CI 入口。
- [ ] Windows/Linux/macOS 三平台冒烟证据。

验收：新机器仅凭 README + 一条 init 命令完成最小闭环；Doctor 在低版本 JDK 下输出可读诊断。

### P0-2 生产入口安全链

现状：TCP 生命周期只有最小切片；无真实鉴权、TLS、防重放、网关边界；KCP/WebSocket/
JSON/Protobuf 仍是 fail-fast 或未实现。

待办（按序）：

- [ ] `AuthenticationProvider` SPI（不内置 JWT/账号系统，由上层注入）。
- [ ] `SecurityContext` 统一认证上下文，贯穿 TCP/HTTP/RPC。
- [ ] `ReplayProtection` SPI（nonce/时间窗/序列号边界）。
- [ ] `TlsMaterialProvider` SPI 与证书轮换边界。
- [ ] 连接准入与请求准入两级策略（含黑名单、限流、慢连接淘汰）。
- [ ] 稳定错误码：未认证、过期、重放、限流、拒绝。
- [ ] 可信来源 IP 与代理信任边界（不信任任意 `X-Forwarded-For`）。
- [ ] focused tests：握手超时、鉴权失败、重放、限流、TLS 拒绝明文。

验收：公网入口的认证/重放/限流/TLS 有 SPI、默认 fail-fast 语义与测试；文档明确不内置账号系统。

### P0-3 Adapter 周期健康与故障恢复

现状：PAF1 只覆盖启动期（create -> start -> startup health -> running）。缺周期健康、
degraded、恢复、熔断、故障转移、Kafka 重平衡、Nacos 节点切换、数据库连接恢复。

待办：

- [ ] 统一 `RuntimeAdapterHealth` SPI：`snapshot() / probe()`。
- [ ] 统一 `RuntimeAdapterRecovery` SPI：`onFailure(FailureContext)` 返回恢复决策。
- [ ] 统一 `RuntimeAdapterBudget`：startup/operation 超时、maxInFlight、maxRetry、退避。
- [ ] 状态机冻结：`ready -> degraded -> recovering -> ready/failed -> drain -> stop -> close`。
- [ ] Kafka：broker 重启、rebalance、迟到响应、pending 恢复语义。
- [ ] Nacos：节点切换、订阅恢复、健康传播。
- [ ] 数据库：连接重置、池耗尽、恢复后对账。
- [ ] 故障注入 focused tests + 外部 Compose 恢复验证（复用
  `scripts/VerifyKafkaNacosExternal.ps1`、`scripts/VerifyRepositoryDrivers.ps1`）。

验收：所有生产 Adapter 共享同一健康/恢复/预算契约，并有注入测试与外部恢复证据。

### P0-4 GM 生产安全运营

现状：已有授权窄切片、标准入口、审计记录/存储/传输边界契约（见
`docs/gm-standard-entry-persistence-contract.zh-CN.md`）；缺真实身份、持久 RBAC/IP/审批、
生产 REST/RPC 适配器、审计查询留存。

待办：

- [ ] GM REST adapter（上层模块，依赖 zero-net + zero-gm；核心不反向依赖 Netty）。
- [ ] GM Kafka RPC adapter（上层模块，复用 correlationId/traceId/timeoutAt；副作用操作禁用 oneway）。
- [ ] `GmIdentityProvider` SPI（认证主体由应用注入，缺失 fail-closed）。
- [ ] 审计查询/分页端口与保留/归档策略边界。
- [ ] 解析失败、未知命令、参数错误的全链路审计覆盖（当前 `GmAuditEvent` 不覆盖这些场景）。
- [ ] 操作幂等键持久化扩展点（防 Kafka 重投/重放副作用）。
- [ ] break-glass 紧急权限流程边界。
- [ ] focused tests：REST 非法 body/过大 body/缺身份、RPC 重复 correlation/迟到响应/超时。

验收：适配器在上层模块、有测试；核心仍不依赖 Netty/Kafka/数据库；文档保持
`productionReady=false` 边界。

### P0-5 API/SPI 兼容门禁

现状：无 japicmp/Revapi/Clirr/baseline；quality 只有 Checkstyle/PMD/SpotBugs/JaCoCo 报告。

待办：

- [ ] 引入 japicmp（或等价）对 `zero-runtime`、`zero-core`、`zero-protocol`、
  `zero-rpc-common`、`zero-data` 做 API diff 门禁。
- [ ] 公共类型稳定性标注（EXPERIMENTAL/INCUBATING/STABLE/DEPRECATED）。
- [ ] 配置 key、协议 ID、provider ID 纳入兼容检查。
- [ ] 仓外 consumer compile test（最小依赖闭包编译）。

验收：破坏性公共 API/配置/协议变更在 CI 被自动拦截并要求迁移说明。

---

## 4. P1：近期完善

### P1-1 统一配置 Schema（machine-readable）

- [ ] 配置注册表：key/env/type/required/default/sensitive/profile/adapter/reloadable/
  deprecated/replacement/validation。
- [ ] 自动生成文档表格、env 清单、unknown-key 与 deprecated-key 检查、配置 lint。
- [ ] 配置 schema version 与迁移报告生成。

### P1-2 依赖边界与消费者矩阵

- [ ] Maven dependency convergence / duplicate classes / forbidden dependency 检查。
- [ ] effective POM 与传递依赖核查（补强 `ZeroArchitectureGuard` 的静态局限）。
- [ ] examples 统一 parent，禁止子模块漂移插件版本。
- [ ] 固定消费者矩阵进 CI：minimal / event-actor / protocol / single-redis /
  custom-provider / production / external-provider。

### P1-3 协议演进与代码生成

- [ ] 协议兼容 diff（禁止删字段/重排/改类型/改 ID 自动阻断）。
- [ ] wire format golden files 与多语言生成一致性测试。
- [ ] 正式 Maven plugin 与生成产物缓存。

### P1-4 数据与缓存生产语义

- [ ] dirty tracking、批量读写、事务边界。
- [ ] 在线数据持久化链：actor 内存 -> 脏标记 -> 有界持久化队列 -> 批量 flush ->
  重试/死信 -> 恢复对账。
- [ ] outbox/inbox 与幂等执行记录。
- [ ] Redis Cluster / 数据库分片 / 网络分区恢复 / 连接池泄漏检测。

### P1-5 可观测性生产化

- [ ] 滚动文件 sink 与 Kafka sink（批量、背压、重试、降级、最大丢失窗口）。
- [ ] Prometheus endpoint 认证/TLS/限流；远程写或高可用采集。
- [ ] 告警真实通知渠道、去重、静默、抑制。
- [ ] 全链路 TraceId：HTTP/TCP -> Actor -> RPC -> DB -> MQ 统一传播。
- [ ] 观测 SLO：日志丢失窗口、指标成功率、告警延迟、sink backlog 上限。

### P1-6 发布、升级与恢复

- [ ] 具体版本迁移实例（如 `docs/migrations/0.1.0-runtime-composition.zh-CN.md`），
  不再只有模板。
- [ ] Release gate 自动化：API diff、迁移文档存在性、制品校验、SBOM、签名、回滚证据。
- [ ] 备份/恢复实测：PostgreSQL PITR、Mongo/Redis/Kafka/Nacos 恢复、RPO/RTO 记录；
  正常停启不得外推为断电/灾备能力。
- [ ] external tests 进 nightly 与 release-candidate 门禁（保留 PR 快速 local gate）。

### P1-7 容量与性能证据

- [ ] 为 protocol codec、Actor scheduler、net frame、RPC pending、Repository、Cache、
  AOI broadcast、scene movement、frame tick、NPC tick 建立可执行阈值（填充空的
  `performance-gate` profile 或改为明确 opt-in 语义）。
- [ ] nightly benchmark + 固定环境/JVM 参数 + 原始 JMH 输出归档。
- [ ] JaCoCo 分支覆盖率阈值（至少覆盖 planner、rollback、fail-fast、network 状态机、
  RPC pending、授权、catalog）。
- [ ] 高风险路径 mutation/故障注入测试：planner 失败、startup health 超时、资源关闭失败、
  broker/db 重启、重复 RPC、缓存击穿、慢消费者、坏配置。

---

## 5. P2：按产品范围决定

以下不急于实现；实现前先确认真实需求，避免为了“看起来全面”扩大支持面：

- WebSocket / KCP / JSON / Protobuf 正式 Adapter（当前 KCP 为 fail-fast 占位，
  `zero-net/.../ServerFactory.java`）。
- 生产 HTTP/REST 通用路由（当前 HTTP 仅最小模型）。
- 玩法模块生产化（每个模块独立走 promotion checklist，不要并入业务规则）：
  - `zero-room`：跨进程 ownership、匹配 SPI、持久化、崩溃恢复、迁移。
  - `zero-aoi`/`zero-state-sync`：空间分区、广播扇出、带宽预算、跨进程 AOI、协议版本兼容。
  - `zero-frame-sync`：rollback、replay、确定性校验、frame ack、客户端追赶。
  - `zero-npc`：行为树/寻路 SPI、优先级调度、持久化、跨进程 owner。
  - `zero-ranking`：Redis Cluster、赛季一致性、结算幂等、奖励接口、归档、跨服同步。
  - `zero-world`：跨进程 ownership/handoff、双写冻结、崩溃恢复、自动再平衡。
- CGLIB/ClassLoader 热更（当前为 design-boundary）。
- 数据中台事件与活动热更。

---

## 6. 五条生产主链（优化主线）

所有切片应服务于以下链条之一；完成五条链后，项目才从“模块化原型框架”进入
“可复用的服务器基础平台”：

```text
1. 入口安全链
   TLS -> Authentication -> Authorization -> Replay protection -> Rate limit

2. 状态可靠链
   Actor -> Dirty tracking -> Persistence queue -> Retry -> Reconciliation

3. 分布式调用链
   Discovery -> RPC -> Timeout -> Idempotency -> Recovery -> Rebalance

4. 运营观测链
   Trace -> Log -> Metric -> Alert -> Audit -> Retention

5. 发布恢复链
   Schema compatibility -> Backup -> Drain -> Rollout -> Rollback -> Restore
```

---

## 7. 推荐实施顺序

| 阶段 | 内容 | 对应条目 |
| --- | --- | --- |
| 第一阶段：生产脊柱 | 统一入口、配置 schema、API 兼容门禁、Adapter 健康/恢复、认证/TLS 边界、GM 适配器、观测生产化 | P0-1~5、P1-1、P1-5 |
| 第二阶段：可靠运营 | 持久化链、幂等、外部恢复、GM 审计留存、备份实测、容量基线、发布门禁 | P0-3/4 延伸、P1-4、P1-6、P1-7 |
| 第三阶段：玩法基建 | 按需求逐个模块 promotion（room -> aoi/sync -> frame -> npc -> ranking -> world） | P2 |
| 第四阶段：协议扩展 | WebSocket/KCP/JSON/Protobuf 按需 | P2 |

---

## 8. 标准切片工作流（每个优化项都照此执行）

```text
1. 选取本文一个未完成条目，创建 tasks/active/<task-name>/
   REQUIREMENTS.md / PLAN.md / RISK.md / VERIFY.md
2. 只读审计现有代码，确认不重复已有实现
3. 高风险项（公共 API/SPI/协议/线程/存储/权限/依赖方向）先暂停并确认
4. 最小实现 + focused tests（稳定 marker 输出）
5. 运行第 9 节验证命令；涉及 reactor/依赖边界变化时跑 full Stage 0
6. 更新 gap ledger / goal audit 证据、本文档条目状态、CHANGELOG
7. 归档 tasks/active -> tasks/archive，保持 VERIFY.md 中的证据可复查
```

---

## 9. 验证命令速查

```bash
# 环境（需 JDK 21；当前机器可用 D:\env\jdk21，通过 JAVA_HOME 切换）
java scripts/ZeroLocalDoctor.java

# 模块级 focused tests
mvn -B -ntp -q -pl zero-gm -am test

# 质量门禁（Checkstyle/PMD/SpotBugs/JaCoCo）
mvn -B -ntp -q -Pquality -pl zero-gm -am verify
mvn -B -ntp -Pquality verify            # 全仓

# 本地集成
mvn -B -ntp -Pintegration-tests verify

# 真实外部组件（需显式提供环境，不默认运行）
mvn -B -ntp -Pexternal-tests verify

# 架构与证据
java scripts/ZeroArchitectureGuard.java
java scripts/ZeroFrameworkGapLedger.java
java scripts/ZeroGoalCompletionAudit.java

# 统一验收
java scripts/ZeroStage0Acceptance.java --level quick
java scripts/ZeroStage0Acceptance.java --level full

# 外部故障恢复（隔离 Compose，结束后自动清理）
pwsh scripts/VerifyKafkaNacosExternal.ps1 -Resilience
pwsh scripts/VerifyRepositoryDrivers.ps1 -Resilience
```

接手第一步：在干净环境重跑 Doctor 与 Stage 0 quick，确认当前工作树真实状态。

---

## 10. 明确不做

- 不实现具体游戏玩法规则（战斗、任务、经济、匹配策略、活动逻辑）。
- 不把账号/JWT/SSO/审批产品内置进框架；只定义 SPI。
- 不让 `zero-gm`/`zero-runtime` 依赖 Netty、Kafka 或具体数据库驱动。
- 不引入万能 IoC、classpath 隐式扫描或隐式外部服务连接。
- 不在 Netty IO/Actor 线程做阻塞远程 IO 或无界等待。
- 不把示例玩法代码升级进框架核心。
- 不执行未经确认的 commit/push/tag/发布/破坏性迁移。
- 不在有充分证据前调整 `productionReady` 标记。

---

## 11. 最终完成审计清单

逐项对照，全部有新鲜证据后才能重新评估目标状态：

- [ ] 新机器一条命令完成环境预检 + 最小闭环（P0-1）。
- [ ] 当前 commit 的 Stage 0 full 新鲜通过且失败/跳过可见。
- [ ] API/SPI/配置/协议兼容门禁在 CI 生效（P0-5）。
- [ ] 所有生产 Adapter 有周期健康、恢复、预算与故障注入证据（P0-3）。
- [ ] 入口安全链完整：认证/重放/限流/TLS 有 SPI 与测试（P0-2）。
- [ ] GM 有生产 REST/RPC 适配器、持久 RBAC/审批、审计查询留存（P0-4）。
- [ ] 状态可靠链落地：dirty tracking/持久化队列/对账（P1-4）。
- [ ] 分布式调用链落地：超时/幂等/恢复/重平衡（P0-3 延伸）。
- [ ] 观测链落地：生产 sink/告警/trace/SLO（P1-5）。
- [ ] 发布恢复链落地：迁移实例/备份实测/回滚证据/签名 SBOM（P1-6）。
- [ ] 容量、p95/p99、长稳有可复现证据与 CI 门禁（P1-7）。
- [ ] README、quickstart、module-map、能力矩阵、gap ledger、本文档状态一致。
- [ ] `productionReady` 仅在上述证据完整后调整；否则保持 false。
