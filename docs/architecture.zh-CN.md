# zeroServer 总体架构

本文说明分层职责与设计方向。当前模块、入口和依赖以[模块图](module-map.md)为准；实现程度见[能力矩阵](capability-matrix.zh-CN.md)。实际装配从[按需装配指南](guides/modular-composition-guide.zh-CN.md)开始。

## 1. 架构目标

zeroServer 采用四层架构：

```text
运营平台接口层
游戏业务抽象层
基础设施适配层
核心内核层
```

核心原则：

- 核心内核稳定、低依赖、高性能。
- 基础设施通过 SPI/Adapter 插拔。
- 游戏业务通过事件驱动和服务模块化实现。
- 运营接口通过标准 REST API、Kafka RPC 和审计体系对外提供能力。

## 2. 核心内核层

职责：

- 生命周期。
- 配置抽象。
- 线程域。
- Actor 与调度抽象。
- 事件总线核心。
- 错误模型。
- SPI 注册。
- 基础工具。

禁止：

- 直接依赖 MongoDB、Redis、Kafka、Nacos 等具体中间件。
- 直接承担业务逻辑。
- 在热路径中依赖不可控反射。

建议模块：

- `zero-core`
- `zero-event`
- `zero-actor`
- `zero-protocol`
- `zero-codegen`

## 3. 基础设施适配层

职责：

- 网络通信。
- RPC。
- 数据库。
- 缓存。
- 服务发现。
- 监控。
- 日志落地。

建议模块：

- `zero-net`
- `zero-rpc`
- `zero-rpc-kafka`
- `zero-data`
- `zero-data-mongo`
- `zero-data-redis`
- `zero-data-postgresql`
- `zero-cache`
- `zero-discovery-nacos`
- `zero-log`
- `zero-monitor`

## 4. 游戏业务抽象层

职责：

- 玩家。
- 实体。
- 场景。
- 事件 BO。
- GM 指令抽象。
- 活动插件接口。
- 数据变更事件。

建议模块：

- `zero-game`
- `zero-scene`
- `zero-player`
- `zero-gm`
- `zero-hot-update`

当前阶段 3 已创建首版原型级业务抽象模块：

- `zero-game`：提供游戏业务执行域、请求上下文和 Actor 投递网关。
- `zero-player`：提供登录、会话绑定、玩家在线数据加载、Repository / Cache 接入和查询基础 API。
- `zero-scene`：提供进入场景、实体坐标、移动、离开场景、实体列表和查询基础 API。

这些 API 用于让登录、玩家加载、进入场景、移动等通用行为可以直接接入 starter 原型；生产级鉴权、持久化、分布式 actor、GM 审批和完整审计仍需后续专项。

阶段 3 当前完整闭环验证通过 `Stage3GeneratedBoFullLoopIT` 覆盖，并由 `integration-tests` profile 在无外部中间件环境执行：

```text
.si 协议
  -> codegen 生成 DTO / codec / BO / dispatcher
  -> zero-player / zero-scene
  -> actor lane
  -> Repository / Cache / scene state
  -> 日志 / 指标 / GM 查询
```

## 5. 运营平台接口层

职责：

- REST API。
- 统一响应结构。
- RBAC。
- IP 白名单。
- 审批流。
- dry-run。
- 审计日志。
- GM 指令。
- 配置发布。
- 活动热更触发。

后台 Web 项目不是 zeroServer 核心的一部分。zeroServer 只提供标准响应式 API、接口实现、鉴权和示例。

## 6. 部署形态

### 6.1 单进程全模块运行

适合：

- 本地开发。
- 最小原型。
- 单服小规模项目。
- 自动化测试。

特点：

- 可不依赖 Docker。
- 可使用内存实现或轻量本地实现。
- 保持与分布式模式一致的业务 API。
- `zero-server-starter` 默认通过 `LocalRuntime.create()` 装配本地无 Docker 组件，`ZeroServerApplication` 统一持有、启动和停止 `GameRuntime`。
- 业务项目需要替换单个组件时，通过 `LocalRuntime.builder()` 返回的 `LocalRuntimeBuilder` 按 typed capability 与 provider ID 显式覆盖；构建前可调用 `diagnose()`，构建后可通过 `GameRuntime.plan()` / `report()` 查看不含敏感配置值的装配诊断。
- 阶段 3 原型可以通过 `ZeroRuntimeExecutors.localPrototype` 使用 starter 管理的 logic、actor、remote IO 和 background 执行域。
- 真实 Kafka、MongoDB、Redis、PostgreSQL、Nacos Adapter 不属于 starter 默认本地装配；改变生产级 opt-in 契约前应提交 GitHub Design Proposal 并完成维护者评审。
- starter 当前不做 classpath 自动 SPI 装配，避免引入 Adapter 依赖后隐式改变默认启动行为。

### 6.2 分布式多进程运行

适合：

- 正式生产环境。
- 多项目复用。
- 大规模连接。
- 跨服与微服务拆分。

特点：

- Nacos 服务发现可选。
- Kafka RPC 与消息转发。
- MongoDB/Redis/PostgreSQL 分工存储。
- Prometheus/Grafana 监控。
- 分布式 Actor gateway 采用显式 route / remote gateway 边界：`LaneKey` 只表示线程绑定对象，远程地址、topic、consumer group 和实例 metadata 由 `ActorRoute` 与 RPC discovery 协作承载。
- 远程 Actor 状态不可直接引用，跨进程状态修改仍必须通过消息投递到目标进程的本地 `ActorScheduler`。

### 6.3 阶段 1 中立装配边界（1B/1C/1D 已实现）

只依赖 `zero-core` 的中立 `zero-runtime` 已进入 Reactor 和 BOM，并实现显式 catalog、preset、profile policy、typed config、依赖图、资源事务、启动健康、安全诊断和共享 capability model。Local Starter、示例、生成器和七类模板已经迁移。1D 已将 Kafka RPC、MongoDB、Redis、PostgreSQL、Nacos 和 production network 正式化为显式 provider；`ZeroProductionRuntime` 直接实现 `GameRuntime`，client 统一由中立 build resource ledger 管理，业务只访问 RPC、`DataService`、`CacheService`、`ServiceDiscovery`、resolver 与 network lifecycle 等 typed capability，不公开驱动对象。

该内核遵守以下边界：

- profile 是运行政策，preset 是可打印的显式选择清单，不因 classpath 改变组件。
- 本地默认装配继续禁止外部连接；production 关键能力禁止回退本地实现。
- 图规划只发生在启动路径，业务热路径使用构造期取得的不可变 binding。
- planning/config/create 与 lifecycle start/startup health 使用独立累计 deadline；资源创建耗时不占用启动阶段预算。
- 运行时拓扑不参与 CSV 业务配置热替换；拓扑变化通过重建 single-use runtime 完成。
- 1B/1C 没有改变线程/Actor、协议、RPC、存储、缓存或日志语义。

完整方案、公共契约、迁移策略和停止门见[模块化运行时装配设计](reference/modular-runtime-assembly.zh-CN.md)。1D 受影响模块门禁已通过；全仓与阶段 0 full 结果以任务验收档案中的最新记录为准。真实外部中间件和生产容量仍属于后续验证范围。

## 7. 演进路线

zeroServer 从 Actor 集群向微服务演进：

```text
单进程模块化
  -> 多 Actor 分区
  -> 多进程 Actor 集群
  -> 服务接口化
  -> 微服务拆分
```

演进过程中业务层应尽量保持事件接口与服务接口稳定。
