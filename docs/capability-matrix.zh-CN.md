# zeroServer 能力矩阵

本文给出 `0.1.0-SNAPSHOT` 的实现状态和明确边界，核对日期为 2026-09-18。状态只描述当前仓库代码与测试，不等同于生产容量或稳定性承诺；`productionReady=false`。

## 状态定义

| 状态 | 定义 |
| --- | --- |
| `implemented` | 已有运行时实现和自动测试，可用于当前声明范围 |
| `minimum-slice` | 已实现最小语义与 focused tests，但生产范围仍有限 |
| `prototype` | 可在本地示例/脚手架中运行，公共契约尚未冻结 |
| `design-boundary` | 已有 SPI 或设计文档，完整实现尚未交付 |
| `not-proven` | 没有足够证据，禁止对外承诺 |

## 核心运行时

| 能力 | 状态 | 已有 | 主要缺口 |
| --- | --- | --- | --- |
| 生命周期与配置 | implemented | 状态、启动/停止、逆序回滚、配置快照、外部 properties、SPI | 集群配置发布与完整动态治理 |
| ErrorCode/异常 | implemented | 分类 ErrorCode、统一异常、错误日志绑定 | 业务项目仍需定义自己的稳定错误码空间 |
| 事件总线 | implemented | 优先级、拦截器、失败后继续派发并汇总异常、有界内存死信、TraceId；默认不自动重试 | 分布式事件总线、持久死信和全局顺序 |
| Actor 调度 | implemented | lane、同 lane 串行、跨 lane 并行、外部执行器 | 集群再平衡、迁移、持久邮箱和容量结论 |
| 受管 Scheduler | minimum-slice | once、fixed-delay、fixed-rate、取消、失败策略、观察器 | cron、持久任务、集群唯一执行、生产高频 tick |

## 协议、网络与 RPC

| 能力 | 状态 | 已有 | 主要缺口 |
| --- | --- | --- | --- |
| Zero Binary Protocol | implemented | Reader/Writer、Buffer、Frame、nullable、集合、注册表 | 完整跨版本演进工具和更多语言性能证据 |
| 协议 DSL/Codegen | implemented | Java/C#/TypeScript/GDScript、DTO/Codec/EventBO/Dispatcher/ErrorCode/测试/文档 | 更多 IDE 集成、正式制品发布和兼容 diff 工具 |
| Netty TCP/UDP/HTTP | implemented | 最小服务器、连接、Frame 桥、TCP 粘包拆包 | WebSocket/KCP 真实 Adapter、完整网关 |
| 生产 TCP 生命周期 | minimum-slice | 状态机、握手、异步鉴权端口、心跳、预算、限流、重连协调、observer | TLS/WAF/DDoS、真实账号鉴权、容量/长稳、完整背压 |
| RPC 抽象 | implemented | request/response、oneway、broadcast、代理、路由、超时 | 完整重试策略、流式 RPC、跨集群治理 |
| Kafka RPC | minimum-slice | producer/consumer、reply、pending、超时轮、资源回收、遥测 SPI | 完整重平衡、跨机房、故障注入、容量和长稳 |
| Nacos discovery | minimum-slice | 注册、查询、订阅、健康更新、RPC metadata | Nacos 集群/鉴权规模验证、订阅背压和灾备 |

## 游戏业务

| 能力 | 状态 | 已有 | 主要缺口 |
| --- | --- | --- | --- |
| 游戏执行域 | implemented | 请求上下文、Actor 投递网关 | 正式多项目业务模块规范仍需实践验证 |
| Player | prototype | 登录、会话、加载、查询、Repository/Cache 接入 | 真实鉴权、断线恢复、跨服迁移、在线数据长稳 |
| Scene | prototype | 进入、移动、离开、实体状态和快照 | AOI、广播、tick、跨场景/跨服迁移 |
| Room | minimum-slice | `zero-room` 本地内存房间、容量、幂等、重连窗口、快照与统计；状态写入绑定 room lane | 生产持久化、跨服、匹配、容量与长稳未证明 |
| AOI/状态同步 | minimum-slice | `zero-aoi` 单 owner 内存 AOI、可见性事件、`zero-state-sync` snapshot/delta baseline 校验、focused tests、local example 与 capacity marker；生产就绪为 false | 广播扇出、跨服迁移、带宽/容量压测、协议冻结与长稳未证明 |
| 帧同步 | minimum-slice | `zero-frame-sync` 固定帧 runtime、输入幂等与迟到策略、focused tests、`examples/frame-sync` 本地示例、广播顺序和容量边界；生产就绪为 false | rollback/回放、确定性跨端验证、真实传输、生产容量与长稳 |
| NPC tick | minimum-slice | `zero-npc` Actor-owned zone 生命周期、bounded tick budget、行为失败隔离、focused tests、local example 与容量边界；生产就绪为 false | 完整行为树/寻路/战斗 AI、持久化、跨服、生产容量与长稳 |
| 排行榜/赛季 | minimum-slice | `zero-ranking` Actor-lane-owned 本地榜单、显式 `SET/MAX/ADD` 合并、稳定排序、有界 Top、赛季状态机、快照与幂等结算；生产就绪为 false | Redis sorted set、跨服榜、奖励资产发放、持久化归档、补偿、生产容量与长稳 |
| 开放世界分片 | minimum-slice | `zero-world` 本地 world/shard、实体归属、迁移状态机、不可变快照和幂等测试 | 跨进程所有权、可靠交接、恢复、再平衡和容量 |

## 数据与缓存

| 能力 | 状态 | 已有 | 主要缺口 |
| --- | --- | --- | --- |
| Repository/DataService | implemented | CRUD、分页、版本、mapping、envelope、本地实现 | 完整 dirty tracking、批量和事务抽象 |
| MongoDB Adapter | minimum-slice | envelope store、driver timeout、健康检查、安全文本 | 集群/分片、批量、网络分区和长稳 |
| Redis Data Adapter | minimum-slice | snapshot、追加日志、真实 Redis store、超时和健康 | Redis Cluster、恢复演练、磁盘日志生产治理 |
| PostgreSQL Adapter | minimum-slice | envelope table、连接 timeout、健康检查 | 连接池策略、迁移、批量和关系模型工具 |
| L1/L2 Cache | implemented | cache-aside、single-flight、负缓存、版本、Redis L2、统计 | 热点倾斜、集群失效、网络分区和容量 |

## 运营与可观测性

| 能力 | 状态 | 已有 | 主要缺口 |
| --- | --- | --- | --- |
| 结构化日志 | minimum-slice | 固定字段、Appender/Sink 隔离、双安全门、脱敏、ErrorCode | 生产 file/Kafka Sink、批量、背压、重试和归档 |
| 指标/告警 | minimum-slice | 内存注册表、低基数约束、Prometheus 文本、JDK 嵌入式 HTTP Endpoint（显式 bind/stop、`/metrics`、`/health`）和规则 | 认证/TLS、远程写、真实告警通道和容量 |
| GM DSL/审计 | minimum-slice | 命令、dry-run、授权/CIDR/审批事实门控、标准入口、审计查询/幂等/break-glass 端口及内存实现；独立 `zero-gm-rest` 请求处理适配 | 真实 HTTP 监听/身份、Kafka RPC 接线、持久 RBAC/审批/审计/幂等和解析失败全链路审计 |
| 入口安全 | minimum-slice | `zero-security` 认证/重放/TLS 材料/可信来源契约；TCP 安全策略和上下文传播 | 真实证书装载与轮换、全传输安全接线、分布式限流和公网验证 |
| Adapter 恢复 | minimum-slice | `RuntimeAdapterHealth/Recovery/Budget`、状态机、有界恢复编排和注入测试 | 各真实驱动的周期健康、连接重建、恢复对账和故障演练 |
| CSV 配置热更 | minimum-slice | 解析、校验、不可变快照、原子替换、失败保旧、WatchService | 多文件事务、远程发布、审批、集群同步 |
| CGLIB/ClassLoader 热更 | design-boundary | 风险边界和接口方向 | 实际代理、签名、备份、集群同步和回滚实现 |

## Starter 与部署

| 能力 | 状态 | 已有 | 主要缺口 |
| --- | --- | --- | --- |
| 阶段 0 本地开箱验收 | implemented | quick/full 统一入口、确定性摘要、独立日志、首错停止、示例与七类脚手架黑盒验证 | 不覆盖真实中间件、容量、长稳或生产就绪 |
| 本地 Starter | implemented | 无 Docker 默认装配、Builder 覆盖、执行域、诊断、生命周期；`ZeroServerTcpApplication` 管理显式 TCP 启停，已有 `local + net` Server 模板 | 新生成 net 工程装配 API 不匹配，当前编译失败；不是生产线程池/容量策略 |
| Production Starter | minimum-slice | 严格 selector、必填配置、启动健康/预算、逆序资源事务、安全异常 | 周期健康、自动恢复、完整熔断、硬 wall-clock 取消 |
| 模块化运行时装配 | minimum-slice | 中立 `zero-runtime`、显式 catalog/preset/profile、typed config、最小依赖图、事务回滚、独立 assembly/startup deadline、启动健康、single-use、安全诊断、共享能力模型、Local Starter 与生成器迁移；Kafka RPC、MongoDB data、Redis shared resource/data/cache、PostgreSQL data、Nacos discovery/RPC resolver 和 network lifecycle provider 已正式接入；Production 门面直接实现 `GameRuntime` | 周期健康、自动恢复、每组件独立预算、完整 production policy 和真实中间件故障验证仍待后续阶段 |
| Docker/编排 | minimum-slice | `deploy/docker-compose.yml` 提供可运行的 Compose 基线：环境变量、非 root、只读根文件系统、healthcheck、内部网络、资源上限、可选 Redis/PostgreSQL 和 secret 路径占位 | app 镜像必须由项目提供；不含 TLS/WAF/DDoS、滚动发布、自动恢复、跨主机灾备、容量或生产就绪证明 |
| 备份与恢复流程 | design-boundary | 提供中文部署基线和备份恢复 Runbook 模板，覆盖 PostgreSQL/Redis 边界、外部依赖、校验与恢复演练清单 | 各项目仍需实现并演练实际备份、PITR、RPO/RTO、加密保留、权限和灾备方案 |

## 性能证据

| 方向 | 状态 | 证据 |
| --- | --- | --- |
| 协议 Codec | implemented | Java 21 下 zero-proto/Protobuf/FlatBuffers 同 DTO 横向微基准 |
| 日志/指标/脱敏/observer | implemented | opt-in `zero-benchmarks` JMH 1.37 |
| Actor、Net Frame、RPC Pending、Repository、Cache、Scene | not-proven | 有实现和测试基础，没有可承诺的隔离容量结果 |
| 生产容量、p99、长稳、SLA | not-proven | 当前禁止宣称 |

这些基准为指定版本和环境下的历史证据，不是本轮重测。完整数字和方法见[性能设计与基准](operations/performance.zh-CN.md)。

## 最近交付与验证边界

- 2026-09-18 实测：默认七类本地模板与 `local + net` 必须分开验收；net 生成装配存在编译错误，详见[本轮核对报告](reports/documentation-audit-20260918.zh-CN.md)。

- 脚手架已有 ownership manifest、plan/diff、升级事务与回滚；流程见[迁移说明](migrations/20260914-scaffold-ownership-manifest.md)。
- Kafka 中心—逻辑三模块及双 JVM 验收脚本已存在；历史真实 broker 验收记录为 blocked，当前不能宣称外部链路通过，见[双进程说明](migrations/20260915-center-logic-kafka-two-process.md)。
- 缓存、事件、持久化、帧同步、房间、状态同步、榜单、AOI、协议和 RPC 的 9 月 17 日修复已在当前工作区实现，行为变化见[修复迁移说明](migrations/20260917-bug-report-verification.md)。
- 平台 gate 的历史 CI job 结论与 artifact 内容审查分开记录，见[平台验证说明](migrations/20260915-platform-and-production-gates.md)；不推定后续代码已通过三平台验证。

## 使用判断

- 适合：学习框架设计、构建本地游戏原型、验证事件/Actor/协议/代码生成、作为自有项目的二次开发底座。
- 谨慎：用 minimum-slice Adapter 进入受控测试环境，必须补齐项目自己的安全、监控、容量和故障验证。
- 不建议：未经专项工程化和压测直接承载公网生产流量，或把当前 SNAPSHOT 当作稳定兼容发行版。
