# Changelog

zeroServer 的重要用户可见变更记录在此。项目当前处于 `0.x` 开发预览阶段，公共 API、配置和协议仍可能发生破坏性变化。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## Unreleased

### Added

- 首次公开 GitHub 仓库、完整项目首页、贡献指南、安全策略、行为准则、Issue/PR 模板和 Dependabot 配置。
- Java 21 GitHub Actions，覆盖默认测试、Checkstyle、PMD、SpotBugs、JaCoCo、示例和脚手架验证。
- 独立 `zero-benchmarks` JMH 模块以及 Zero Binary Protocol、Protobuf、FlatBuffers 的可复现横向基准。

### Changed

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
