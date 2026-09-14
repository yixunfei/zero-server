# Changelog

zeroServer 的重要用户可见变更记录在此。项目当前处于 `0.x` 开发预览阶段，公共 API、配置和协议仍可能发生破坏性变化。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## Unreleased

### Added

- 场景接入指南、可运行的最小中心—逻辑接口示例；runtime 脚手架新增 Kafka、Nacos、MongoDB、PostgreSQL 组件选择，自动补齐所需依赖并替换相应本地实现。
- `ProductionAssembly.plan()` 提供无资源的实际组件图；新增显式档位 builder overload，补齐 standalone 装配，生成工程可通过配置切换档位。

- P0-4 GM 生产运营边界扩展：新增有界审计查询/留存/归档契约、业务幂等 claim/conflict/TTL、break-glass 一次性授权边界、解析无关安全失败载体和 GM RPC transport-neutral boundary；内存实现仅用于 local/test，不声称真实 HTTP/Kafka/数据库运营闭环。
- 新增 `ZeroUnifiedEntryVerifier`，验证两端命令契约、脚本安全状态管理和 POSIX stop 幂等语义。
- 阶段 0 验收脚本的进程输出采集和超时进程树处理兼容 Java 17 进行预检编译；项目实际构建和运行仍明确要求 Java 21。
- 阶段 1 模块化运行时装配设计与 `zero-runtime` 1B/1C 通用契约：显式 catalog/selection、typed config、确定性依赖图、双资源账本、启动健康、single-use 生命周期、稳定错误码、安全诊断和共享能力模型；Local Starter、生成器、示例及模板已迁移，真实 Adapter provider 留待 1D。
- 1D-0 Production 迁移基础契约：`GameRuntime.optional(...)`、相互独立的 assembly/startup deadline，以及 `standalone`、`external-test`、`production` profile 和中立 data/discovery/resolver/network capability 词汇。
- 1D-1 Kafka RPC 正式 runtime provider：稳定 provider ID、typed startup schema、显式日志依赖、双 RPC capability、mandatory startup health，以及保持不变的安全属性白名单和延迟连接边界。
- 1D-2 MongoDB data 正式 runtime provider：稳定 provider ID、敏感 typed schema、`DataService` 多值贡献、mandatory startup health，以及立即登记到中立 build resource ledger 的 Mongo client。
- 1D-3 Redis provider family：包内共享资源句柄、独立 data/cache provider、单一中立 ledger client、`DataService`/`CacheService` 业务能力和各自 mandatory startup health；未公开 `RedisClient` typed capability。
- 1D-4/5 PostgreSQL 与 Nacos provider：敏感 typed schema、中立 data/discovery/resolver capability、mandatory startup health 和既有启动预算语义。
- 1D-6 production network provider：显式 policy、10 项非敏感 typed config、受管非内联 remote IO executor 和默认/自定义有界限流器。
- 1D-7 收敛：`ZeroProductionRuntime` 直接实现 `GameRuntime`，删除驱动 getter、package-private bridge、重复 resource scope 和旧 network factory。
- 首次公开 GitHub 仓库、完整项目首页、贡献指南、安全策略、行为准则、Issue/PR 模板和 Dependabot 配置。
- Java 21 GitHub Actions，覆盖默认测试、Checkstyle、PMD、SpotBugs、JaCoCo、示例和脚手架验证。
- 独立 `zero-benchmarks` JMH 模块以及 Zero Binary Protocol、Protobuf、FlatBuffers 的可复现横向基准。

### Changed

- runtime 外部 smoke 改为仅诊断，连接地址不写死进业务源码；缺失配置明确输出 incomplete，多来源 Repository 按来源名绑定。详见 [0.x 迁移说明](docs/migrations/20260914-scenario-composition.md)。
- 修复组件间装配超时跳过回滚、健康探针超出累计预算仍进入 RUNNING、诊断冻结集合选择 Builder 三项运行时 bug；均有修复前失败的确定性回归测试。
- 真实 TCP 示例使用框架统一管理的执行器，协议 codegen 移入构建插件依赖；场景消费者验证所选 SDK 与实际 provider 图，完整治理状态保持未完成。

- 修复 GitHub Actions 工作流的 `jobs` 顶层结构，恢复 compatibility、unit、quality、integration、Stage 0 和跨平台入口任务的正常解析。
- 修正 production network provider 对 `SecurityChain.tlsRequired()` 的配置传播，避免安全链要求 TLS 时被网络配置覆盖。
- 将 GM 幂等操作指纹改为带明确分隔符的 SHA-256 摘要，降低短整数 hash 碰撞导致错误复用的风险。
- 同步架构守卫与缺口台账文档中的当前模块数和能力状态；这些同步不改变 `productionReady=false` 边界。

- `zero-runtime` 在 1B 复审中收紧 callback 异常归一化、确定性 catalog/selection 冻结和 startup health 超时取消；公开异常与报告不携带 raw cause 或配置值。
- Local/Production Starter 统一复用 `GameRuntime` 生命周期与双 ledger 契约；Production Adapter 配置、健康预算和回滚已由正式 provider 接入同一组件图。
- Production 累计启动预算现在只覆盖 lifecycle start 与 startup health；planning/config/create 使用独立装配预算，不再从资源创建阶段提前消耗启动预算。
- 共享 capability model 现在可声明 provider-specific 依赖与实现制品，使具体实现依赖不会被错误提升为所有 provider 的能力依赖，生成器也能计算完整 provider artifact 闭包。
- 公开文档改为面向使用者、部署者和贡献者组织，移除内部任务、计划和审计材料。
- Maven 项目元数据、SCM 和 Issue 地址更新为 `yixunfei/zero-server`。

## 0.1.0-SNAPSHOT — Development Preview

### Runtime kernel

- Java 21 Maven 多模块工程、统一 Parent/BOM 和生命周期抽象。
- ErrorCode 分类、统一异常、配置快照、SPI 排序与基础执行域边界。
- 本地确定性 Actor 调度、lane 绑定、事件总线、拦截器、重试/死信基础能力和 TraceId 上下文。
- 受管定时任务契约与本地实现，支持 once、fixed-delay、fixed-rate、取消、限流、失败策略和观察器。

### Protocol and code generation

- Zero Binary Protocol buffer、Frame Codec、payload codec、注册表和 nullable/集合编码。
- `.si` 协议 DSL 解析、校验和协议 ID 管理。
- 生成 Java DTO/Codec/EventBO/默认实现/Dispatcher/ErrorCode/测试，以及 C#、TypeScript、GDScript 客户端协议代码和文档。

### Game model

- 游戏业务执行域、请求上下文和 Actor 投递网关。
- 玩家登录、会话、在线数据加载、Repository/Cache 协作和查询基础 API。
- 场景进入、移动、离开、实体坐标和查询基础 API。
- RPG、房间、场景同步、帧同步、NPC Tick、排行榜赛季、开放世界分片脚手架。

### Networking and RPC

- Netty TCP、UDP、HTTP 最小服务器和协议帧桥接。
- 显式启用的生产 TCP 生命周期最小实现：状态机、握手、异步鉴权端口、心跳、入站预算、限流、重连协调和遥测观察器。
- RPC request/response、oneway、broadcast 抽象和公共接口代理。
- Kafka RPC Adapter、pending request、超时轮、reply topic、correlationId、traceId、timeoutAt 与安全资源回收。
- Nacos 服务发现 Adapter 和 RPC 实例解析。

### Data and cache

- 统一 `Repository` / `DataService`、对象映射、数据 envelope 和版本冲突基础能力。
- MongoDB、Redis、PostgreSQL Adapter。
- 本地缓存、分层缓存、Redis L2、自动加载、写回/失效、版本和防击穿/防穿透基础能力。
- Redis 追加式数据日志和本地磁盘过渡实现。

### Operations

- CSV 配置加载与本地原子热重载，支持失败保旧、校验、哈希去重、WatchService 和审计观察器。
- GM 指令 DSL、注册、dry-run、执行编排、审计归因、业务提交状态和统一 ErrorCode。
- 统一结构化日志、敏感字段拒绝/脱敏、处理管线、System.Logger 和监控告警 Sink。
- 内存指标注册表、低基数标签约束、Prometheus 文本导出、Grafana JSON、告警规则和系统指标采集。
- 本地无 Docker Starter 与显式 Production Starter；真实 Adapter 默认不进入本地装配。

### Examples and developer experience

- RPG 本地业务闭环和协议驱动示例。
- 真实 TCP generated dispatcher 示例。
- CSV 配置热重载、受管定时任务和可观测性示例。
- 关键词驱动的项目脚手架生成、运行、结构检查和批量验证工具。

### Known limitations

- 当前不是生产就绪版本，不提供容量、长稳、p99 或 SLA 承诺。
- 生产 TCP 生命周期不包含完整 TLS/WAF/DDoS、真实账号鉴权和完整网关。
- GM 尚未提供完整 RBAC、IP 白名单和审批服务。
- 生产文件/Kafka 日志 Sink、Prometheus HTTP Endpoint 和完整告警推送仍待实现。
- CGLIB 修复、ClassLoader 活动插件和集群热更同步仍处于设计边界。
- 外部组件默认测试不会自动启动 Docker，需要部署方显式提供环境并启用 `external-tests`。
