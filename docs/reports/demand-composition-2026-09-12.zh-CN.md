# 按需组装与业务接入推进方案

本页保存按需装配的历史实施与验证记录。当前接入步骤见[快速上手](../quickstart.zh-CN.md)，当前剩余工作见[优化路线图](../optimization-roadmap.zh-CN.md)。文中阶段计划和计数保留原验证时点。

评估日期：2026-09-06；后续更新：2026-09-08。三批实现、审核后的四阶段修正及运行约束验证均已完成。最终全仓 full 17/17 通过，真实数据库基础契约、停启恢复、并发 CAS 及四种实现各五分钟持续运行通过，详见第 11 节；下一阶段顺序见第 12 节。第 7 至 10 节保留各阶段的历史验收数据。

## 1. 目标与判断

目标是让业务项目只引入需要的组件，通过中立接口使用能力，并能在组合根选择或替换实现。默认本地路径应无需外部服务即可完成业务操作；选择真实 Adapter 后，应有明确配置、可用业务入口与失败诊断。

当前项目已完成阶段 0 和 1B/1C/1D 的运行时装配基础。本轮应延续现有 `zero-runtime`、typed capability、显式 selection 和资源 ledger，补齐从 Maven 依赖到业务接入的闭环。

“可运行”“可选中组件”“可裁剪依赖”“可替换实现”是不同验收项。现有本地全量验收不能单独证明后两项。

## 2. 已具备的基础

| 领域 | 当前实现 | 后续复用方式 |
| --- | --- | --- |
| 中立运行时 | `zero-runtime` 非测试直接依赖只有 `zero-core` | 保持内核依赖方向 |
| 装配规划 | catalog、selection、依赖闭包、冲突与循环检查 | 组件独立注册，共用规划器 |
| 生命周期 | 拓扑启动、失败回滚、逆序关闭、single-use runtime | 各 Adapter 继续登记同一资源 ledger |
| 组件替换 | Local Builder 支持 register、override、replace | 将能力键与 provider 接入点移到对应职责边界 |
| 本地使用 | 五类独立示例、七类脚手架与统一 full 验收 | 作为迁移回归基线 |
| 真实 Adapter | Kafka、MongoDB、Redis、PostgreSQL、Nacos 与 network provider | 复用配置、驱动和现有失败语义 |

## 3. 实施前已核实的缺口

本节保留改造起点。3.1 至 3.3 已通过独立集成层和延迟创建修复；3.4 已由中立 Repository 工厂/角色目录补齐；3.5 已接入按组件选择生成。全量 Starter 继续承担便利组合职责。当前 API 见[按需装配指南](../guides/modular-composition-guide.zh-CN.md)。

### 3.1 运行时最小集合没有对应最小依赖集合

`LocalRuntimePresets.minimal()` 只减少运行时 requirement。`zero-server-starter/pom.xml` 仍直接引入 player、scene、net、rpc、data、cache、gm、hot-update 等模块，因此最小运行时消费者仍携带整套本地依赖。

`zero-server-starter-production/pom.xml` 同时引入本地 Starter、Kafka、MongoDB、Redis、PostgreSQL 和 Nacos。只选 Redis 仍会传递引入其他驱动。

证据：

- [LocalRuntimePresets](../../zero-server-starter/src/main/java/group/zn/zero/starter/LocalRuntimePresets.java)
- [本地 Starter POM](../../zero-server-starter/pom.xml)
- [生产 Starter POM](../../zero-server-starter-production/pom.xml)

### 3.2 可复用的接口和装配代码仍受聚合模块限制

本地能力键集中在 `LocalRuntimeCapabilities`，执行器类型位于 Starter。生产 capability 又引用本地 capability。真实 Adapter provider 是生产 Starter 包内类型，外部组合根无法直接导入这些 provider，必须经过固定 Builder 或另写装配。

`ServiceDiscovery`、实例、查询、订阅等中立接口和本地实现位于 `zero-discovery-nacos`。使用这些接口会引入 Nacos 客户端依赖。这里有明确的职责拆分理由。

证据：

- [RuntimeBasics（基础能力现归属）](../../zero-runtime-bootstrap/src/main/java/group/zn/zero/runtime/bootstrap/RuntimeBasics.java)
- [DataRuntime（数据能力现归属）](../../zero-runtime-data/src/main/java/group/zn/zero/runtime/data/DataRuntime.java)
- [ProductionMongoDataProvider（现位置）](../../zero-runtime-mongo/src/main/java/group/zn/zero/runtime/mongo/ProductionMongoDataProvider.java)
- [ServiceDiscovery（现位置）](../../zero-discovery/src/main/java/group/zn/zero/discovery/ServiceDiscovery.java)

### 3.3 部分默认对象早于选择结果创建

`LocalRuntimeProviders.defaults()` 在 Builder 初始化时创建协议注册表、缓存、监控和死信对象，然后包装成 value provider。即使最终未选择相应组件，这些默认对象也已创建。日志 Pipeline 同样在 `LocalRuntime.builder(...)` 创建。

`logAppender` provider 声明依赖 CONFIG 和 TERMINAL_LOG_SINK，但使用预先捕获的 appender；替换这些依赖不能自然重建日志实现。需要将框架默认对象的创建放入 provider create 阶段，并从 creation context 读取已解析依赖。调用方显式传入的实例需要单独定义资源所有权。

证据：[LocalRuntimeProviders](../../zero-server-starter/src/main/java/group/zn/zero/starter/LocalRuntimeProviders.java)、[LocalRuntime](../../zero-server-starter/src/main/java/group/zn/zero/starter/LocalRuntime.java)。

### 3.4 数据组件装配尚未形成中立 Repository 接入闭环

`DataService` 仅声明 `serviceName()` 和继承的生命周期。Mongo provider 创建并登记 client，但只向业务贡献 Adapter 的 `DataService` 视图。创建真实 Mongo Repository 的现有方法需要具体 `MongoClient` 和数据库名称。

因此，生产 runtime 能启动和报告数据组件状态，并不代表业务已经能仅通过中立能力创建并使用真实 Repository。这是公共接入契约缺口，不能以恢复驱动 getter 解决。

应新增独立的中立 Repository 创建契约，复用现有实体 metadata、codec、envelope 和 CrudRepository；连接资源在 Adapter 内闭包持有。业务服务只接收 Repository。多个数据库应显式命名或绑定到不同角色，避免隐式取集合第一项。

证据：

- [DataService](../../zero-data/src/main/java/group/zn/zero/data/DataService.java)
- [MongoDataAdapter](../../zero-data-mongo/src/main/java/group/zn/zero/data/mongo/MongoDataAdapter.java)
- [ProductionMongoDataProvider](../../zero-runtime-mongo/src/main/java/group/zn/zero/runtime/mongo/ProductionMongoDataProvider.java)

### 3.5 脚手架依赖没有随能力选择裁剪

七类模板当前共享 `LOCAL_CAPABILITIES` 与 `DIRECT_DEPENDENCIES`，都依赖全量本地 Starter。能力模型已有 Maven 坐标，但生成 POM 使用固定 direct dependencies。因此，选择模板尚不能得到按需依赖的业务工程。

应由已选择 provider、能力依赖和模板实际源码需要共同决定依赖。协议代码生成仍属于构建阶段，业务运行时不应引入生成器。

证据：[ScaffoldCatalog](../../zero-codegen/src/main/java/group/zn/zero/codegen/scaffold/ScaffoldCatalog.java)、[ProjectScaffoldGenerator](../../zero-codegen/src/main/java/group/zn/zero/codegen/scaffold/ProjectScaffoldGenerator.java)。

### 3.6 对外文档存在阶段状态漂移

README 的模块表仍将真实 Adapter provider 标为待 1D，active 任务记录和源码已显示 1D 完成。阶段状态、模块说明和公开命令应在迁移时统一更新。

## 4. 建议实施顺序

### 第一批：按需装配基础与依赖隔离（已完成）

1. 保留中立 runtime；把能力键、执行器等公共类型放到与其依赖一致的基础或组件集成模块。
2. 将默认实例创建延后到被选择的 provider create 阶段，验证替换后的依赖确实进入组件构造。
3. 提供轻量组合入口与组件独立接入点。本地全量 Starter 可以继续作为便利组合包；最小消费者直接依赖基础入口和所选组件。
4. 拆出中立 `zero-discovery`，让 Nacos Adapter 单向依赖它；RPC 映射代码的依赖放在相应集成边界。
5. 将真实 Adapter 的 runtime 接入从生产聚合模块释放到独立集成模块，共享配置、诊断、预算和健康支持。生产组合根显式注册组件，新实现接入不要求修改中央 Builder 的固定 Adapter 列表。
6. 同步 Reactor、BOM、依赖守卫、能力模型和仓内引用。针对未发布 0.x 接口直接迁移，提供迁移说明。

按职责确定最终模块粒度；不为每个接口机械新增 Maven 模块，也不把所有可选能力重新集中到一个公共大模块。纯 Adapter 可继续独立于 runtime，集成模块依赖 Adapter 与 runtime。

### 第二批：业务可用的数据替换闭环（已完成）

1. 定义中立 Repository 创建请求与工厂，明确实体类型、名称、codec 版本和存储角色。
2. 为本地与真实数据 Adapter 提供同一业务消费方式，连接资源和关闭权保持在 provider 内。
3. 增加同一业务服务替换本地/MongoDB/PostgreSQL 实现的示例；Redis 数据与缓存按各自接口接入。
4. 验证重复注册、角色缺失、类型不匹配、启动失败和资源关闭。真实服务验证与无需外部服务的契约验证分别记录。

### 第三批：按需生成与开箱验收（已完成）

1. 生成器接收明确组件选择，用共享模型生成 POM、装配代码和非敏感配置样例。
2. 让模板声明实际业务模块需要，减少固定全量能力清单。
3. 提供最小内核、事件/Actor、本地 RPG、单 Redis、自定义实现五类消费者路径。
4. 增加独立消费者的 Maven 依赖闭包检查，将未选中 SDK 的缺席作为机器验收项。
5. 更新 quickstart、组件目录、扩展接入与迁移文档，纳入统一验收和 CI。

## 5. 方案复审

- 只设置 Maven optional 不足以解决问题：聚合入口的静态类型引用和默认构造仍可能要求缺席的类存在，必须验证真实精简 classpath 的运行。
- 新增通用 IoC、隐式 classpath 扫描或运行期下载不能解决职责归属，现有显式 planner 已能承担装配。
- 业务能力不能依赖 Starter 获取中立类型；可选能力之间的依赖由描述符和构建依赖同时表达。
- 替换实现必须覆盖组件创建依赖、生命周期、诊断与资源所有权；不能只替换 runtime 返回的一个值。
- 数据业务接入与依赖裁剪需要各自的验收；通过生命周期测试不能代替一次 Repository 读写。
- 本轮保持 Java 21。协议 wire format、数据 envelope、缓存 key、线程与 Actor 所有权不属于本次改造目标。

## 6. 完成标准

| 场景 | 必须证明的行为 |
| --- | --- |
| 最小消费者 | 只含基础 runtime 与声明能力；无需网络、数据库或消息 SDK 即可启动/停止 |
| 单组件消费者 | event/actor 等组件按实际依赖引入，不通过全量 Starter 获得隐含依赖 |
| 单 Redis 消费者 | 依赖闭包无 Kafka/MongoDB/PostgreSQL/Nacos 客户端；未配置其他 Adapter 仍可装配 |
| 自定义实现 | 业务服务源码不变；注册 provider 并选择后，依赖它的组件获得替换实例 |
| 未选组件 | 不调用框架 provider 工厂，不创建默认 client、缓存或监控资源 |
| 数据替换 | 同一业务代码通过中立 Repository 完成读写，无驱动 getter 或具体 Adapter 强转 |
| 配置失败 | 缺配置、冲突与循环在资源创建前发现；报告可定位且不输出敏感值 |
| 失败回滚 | 中途创建/启动失败后资源关闭一次；清理行为符合现有 ledger 契约 |
| 生成工程 | 独立 Maven 构建、协议生成、测试和运行通过，实际依赖与选择一致 |
| 既有能力 | 全仓 quality、integration 与阶段 0 full 持续通过 |

## 7. 实施前基线

- 当前工作区起始 `git status --short` 无改动。
- 默认 PATH 为 Java 17；复验使用 `scripts/dev-env.ps1` 中配置的 JDK 21.0.4 和 Maven 3.9.8，仅修改本次子进程环境。
- `java scripts/ZeroStage0Acceptance.java --level full`：14/14 通过，失败和跳过均为 0，用时 291813 ms。
- 14 项包括 Doctor、架构守卫、默认测试、quality、无需外部服务的 integration、SNAPSHOT 安装、本地 Starter、五类独立示例、关键词生成原型与七类脚手架批量验证。
- Doctor 为 23/23；架构守卫为 28 modules、17 rules、0 violations、0 warnings。
- 按根 Reactor 模块及当前测试源码过滤陈旧报告后，Surefire 为 495 tests、0 failures、0 errors、0 skipped。本地集成输出 `zero-local-integration=ok|flow=generated-bo-full-loop`。
- 验收日志位于 `target/stage0-acceptance/logs/`，后续执行会覆盖同名日志。
- 未连接用户外部中间件；真实数据库读写和部署能力不在本次基线结论内。

## 8. 第一批实现与验收

- 新增 `RuntimeComposition`、`RuntimeModule` 和延迟 provider 工厂，复用原有规划器和资源账本。
- 拆出 bootstrap、各本地组件集成、无驱动 production 共享层及各真实 Adapter 集成；Reactor/BOM 共 47 个模块。
- 中立发现 API/本地实现迁至 `zero-discovery`，RPC 映射迁至 `zero-rpc-discovery`。
- 默认组件在所选 provider 创建阶段实例化；日志使用最终选定的配置和终端 sink。执行器只在 provider 创建后转交 runtime 管理。
- 补齐独立生产入口 profile 校验、网络配置源转交和应用扩展诊断脱敏。
- 四种独立消费者已通过 Maven Enforcer 和实际 classpath 验证，证明最小/event-actor/discovery/Redis 路径不携带无关组件或 SDK。
- 复审修正后的最终全仓验收 15/15 通过，用时 354248 ms，失败、错误和跳过均为 0；包含 quality、integration、五类示例、七类脚手架与四种独立消费者。
- 按当前 Reactor 和测试源码统计，Surefire 为 505 tests，本地 Failsafe 为 1 test，全部通过。
- 架构守卫为 47 modules、18 rules、0 violations、0 warnings。benchmark 显式 profile 编译打包通过；未执行性能测量。
- `target/` 外 800 个 Java 源文件均未超过 1500 行，最大文件为既有脚本的 1494 行；quality 方法上限 100 行通过。
- 新入口与 0.x 迁移见[按需装配指南](../guides/modular-composition-guide.zh-CN.md)。

以上是第一批验收时的范围；第二、三批的完成情况如下。本地验收未连接真实外部中间件。

## 9. 第二、三批实现与验收

- 中立 `RepositoryDefinition`、`RepositoryFactory`、`RepositorySource`、`RepositoryRequest`、`RepositoryCatalog` 已实现；角色显式映射到命名源，拒绝重复来源、缺失角色及冲突定义。
- 本地、MongoDB、PostgreSQL、Redis 数据 provider 贡献统一来源；复用既有 envelope/codec/CAS，工厂在 runtime 关闭或回滚时先于客户端失效。并发 store 操作与关闭等待有定向测试。
- `examples/repository-composition` 的同一 `BalanceService` 由组合根切换四种来源。本地输出为 `amount=15|version=2`；四种本地 envelope 表示、类型校验和关闭回滚均已验证。
- 新增 `runtime` 模板与 `--components`。模板必需能力和用户组件选择共同决定 POM、`RuntimeAssembly.java`、配置样例、provider/capability 清单。
- 七类业务模板默认只装配 bootstrap、Actor、日志和监控；protocol 是业务编译依赖，codegen 仅为构建插件依赖。RPG 另带实际使用的 player/scene 及其抽象依赖。
- `VerifyGeneratedCompositions` 覆盖最小、事件/Actor、本地 RPG、单 Redis、自定义 Actor 五种独立工程，实际框架依赖分别为 3、7、15、11、15 个。验证编译/运行类路径中未选 SDK、Starter 与 codegen 缺席，且自定义 Actor 被实际选中。
- 标准模型为 22 个 capability、22 个 provider；本地 discovery 和应用 Repository 目录由集成层注册扩展 provider。
- 最终 `java scripts/ZeroStage0Acceptance.java --level full` 为 17/17、420935 ms，失败、跳过均为 0；包括全仓 test/quality/integration、六类业务示例、七类业务模板、四种精简消费者与五条生成路径。
- full 当次 Surefire 516 项、本地 Failsafe 1 项，失败、错误、跳过均为 0。随后复核补齐 discovery/RPC resolver 清单并增加 1 项回归，当前为 517 项；codegen 及依赖模块的 quality/install、全部本地可选组件组合和五种生成消费者再次通过。
- 架构守卫为 47 modules、18 rules、0 violations、0 warnings；821 个非 target Java 源文件最大 1494 行，质量门禁的 1500 行文件/100 行方法限制通过。

业务入口见 [Repository 指南](../guides/repository-composition-guide.zh-CN.md)，生成命令见[脚手架目录](../scaffold-templates.zh-CN.md)。以上为第二、三批完成时的记录；当时未验证真实服务。后续基础契约已通过，见下一节，不能继续将真实读写列为未完成。

## 10. 审核后巩固阶段（已完成）

完整过程见[审核及修正记录](composition-review-2026-09-07.zh-CN.md)。按已确认顺序，五项审核问题修复、公开契约扩展、真实 Repository 契约及用户接入流程均已完成。

- 修复 Mongo 物理集合名碰撞、网络校验旧执行器、Java/CLI 输入边界及 PostgreSQL 必填配置说明；采用直接迁移和定向回归。
- 生成消费者从五条代表路径扩展为十九条：五条代表路径、十二个公开组件和两种混合组装。实际编译、运行、POM/manifest/runtime plan 及未选 SDK 缺席均纳入检查。
- 生成 runtime 提供 `--diagnose`，诊断和创建共用组装定义；全新消费者按文档完成生成、诊断与启动。
- 该阶段全仓 full 为 17/17、503608 ms，Reactor Surefire 527 项、本地 Failsafe 1 项全部通过；quality 与架构守卫通过。本轮因 PostgreSQL 连接池修正涉及生产源码，另执行全仓 full，结果见第 11 节。
- 隔离真实服务契约运行 `aa7a4abfdafd4cef80b28b5a35cee716` 覆盖 local/MongoDB/PostgreSQL/Redis 的 CRUD、版本冲突、命名空间隔离、关闭后访问及外部客户端重建后读取。三个外部 Failsafe 分别 1/1，无失败或跳过，专用容器已清理。

## 11. 运行约束验证（已完成）

用户已确认顺序：隔离服务停启恢复 → 并发 CAS 一致性 → 每种实现持续运行五分钟 → 更新方案与验收记录。

本轮复审后的边界：

1. 沿用中立 Repository 与同一 `BalanceService`，测试组合根共用，业务层不依赖驱动。
2. 停启只操作本轮唯一且经过标签检查的 Compose project，随机选端口后固定本轮绑定，保留客户端与 Repository 观察恢复；故障期间只读，避免不确定写入的重复入账。
3. 外部并发使用独立 runtime/客户端；本地按内存存储实际生命周期共享一个 runtime。验证同版本创建/更新恰好一个成功，并校对最终余额和版本。
4. 持续运行四种实现各 300 秒，同阶段并行；每个实现 4 个工作线程、入账后间隔 10 ms。可配置时长，有任务、原生驱动及测试进程超时。明确冲突才允许有限重试，其他失败不跳过。
5. Redis 测试容器启用 AOF/`appendfsync always`，记录正常停启的持久化前提。结果不外推为进程强杀、断电、网络分区或容量保证。

可重复入口：

```powershell
./scripts/VerifyRepositoryDrivers.ps1 -Plan -Resilience
./scripts/VerifyRepositoryDrivers.ps1 -Resilience
./scripts/VerifyRepositoryDrivers.ps1 -Resilience -SoakSeconds 1800
```

### 验证中发现并修复的问题

- Java 在 Windows 调用 Docker 时，包含引号的 Go 模板参数未按预期传递。容器归属检查已改用 Docker 原生 `--filter label=...`，直接校对完整容器 ID。
- Docker 动态宿主端口在同一容器停止后重新启动时发生变化。脚本改为启动前随机选择空闲端口，并显式固定本轮绑定；重启前后增加端点一致性断言。
- PostgreSQL 持续运行两次失败，Windows 同时记录 TCP 事件 4227：高频建连/断连耗尽可复用本地端点。失败记录为 `82ce22cde5bd48a3a968690580200008` 和 `688a5f58c1c743c4bc82ae0ff6f05006`，后者目录保留 `windows-tcp-events.json`。原始 Repository 每次操作创建物理连接，测试没有重试这类不确定写入。
- PostgreSQL 存储改为依赖标准 `DataSource`；集成模块新增 HikariCP 7.1.0 有界池，默认上限 8，可用 `PostgresqlRuntime.module(maximumConnections)` 调整。池在 provider 被选择后创建，首次借用时连接，先于 Repository 工厂登记以保证逆序关闭时先失效业务访问。纯 Adapter 可以接收应用自带数据源，不依赖 HikariCP。
- 增加连接借还、失败释放、池满等待上限、物理连接复用和关闭顺序回归；生成消费者同时验证未选择 PostgreSQL 时 Hikari 类缺席。验证任务按完成顺序收集结果，任一失败及时取消其他任务；失败报告保存在独立运行目录，不被下次 Maven 清理覆盖。

### 最终本地验收（2026-09-08）

```text
zero-stage0-acceptance=ok|level=full|checks=17|passed=17|failed=0|skipped=0|durationMs=492191|externalMiddleware=false|productionReady=false|outputDir=target/stage0-acceptance
generated-compositions=ok|consumers=19
```

Reactor Surefire 531 项，本地 Failsafe 1 项，失败、错误、跳过均为 0。Checkstyle、PMD、SpotBugs 和 JaCoCo 执行通过；架构守卫为 47 modules / 18 rules，0 violations / warnings。仓库非 target Java 源文件 840 个，最大 1494 行，生产模块及测试的 1500 行文件 / 100 行方法质量门禁通过。新增连接池后同步更新聚合装配的资源数量断言，并验证关闭后待释放资源为 0。

full 同时验证六类独立业务示例、七类业务脚手架、四种精简消费者与十九种按需生成工程。未选择 PostgreSQL 的消费者实际 classpath 中 `com.zaxxer.hikari.HikariDataSource` 缺席。日志保存在 `target/stage0-acceptance/logs/`，该本地验收不访问外部数据库。

### 最终真实驱动复验

使用上述 full 安装的最终产物，运行 `VerifyRepositoryDrivers.ps1 -Resilience`；本轮运行标识为 `3de4b70d9f6e456b8a1770fdf2687ddf`，结果通过。

```text
repository-drivers=ok|backends=4|resilience=True|containersRemoved=true
```

- MongoDB、PostgreSQL、Redis 基础契约的 Failsafe 各 1/1；恢复、并发、持续运行组成的顺序场景 1/1，耗时 327.604 秒。四份摘要均 completed=1、errors=0、failures=0、skipped=0。示例本地 Surefire 5 项通过。
- 三种外部实现均保留原 runtime、客户端和 Repository，通过停止服务时读取失败、启动后读回原余额、继续入账的检查。故障读取耗时分别为 Mongo 2004 ms、PostgreSQL 2009 ms、Redis 不足 1 ms；启动及恢复耗时分别为 6648、3455、3401 ms。
- 每种实现的同时创建、同时更新均恰好一个成功；随后四个工作线程各完成 100 次入账，所有客户端读到余额和版本均为 402。外部实现使用独立 runtime/客户端，本地共享同一 runtime 的内存存储。

五分钟持续运行结果如下，所有客户端最终余额与版本均等于成功入账数，删除后记录数为 0：

| 实现 | 实际运行 ms | 成功入账数 | 明确 CAS 冲突数 | 平均入账延迟 us | 最大入账延迟 us |
| --- | --- | --- | --- | --- | --- |
| local | 300017 | 84073 | 7941 | 135 | 6496 |
| MongoDB | 300014 | 72324 | 34032 | 3069 | 58077 |
| PostgreSQL | 300019 | 69412 | 31038 | 3338 | 28402 |
| Redis | 300024 | 69878 | 23248 | 3860 | 28069 |

延迟包含显式冲突重试；四种负载共用宿主机，运行前段与生成消费者构建同时进行，不能将这些数值当作独立容量基准。PostgreSQL 两次服务端连接抽查均为 4 条工作连接加 1 条检查连接；该记录说明本次工作负载复用了物理连接，不代替小时级资源趋势验证。

环境为 Java 21.0.4、Maven 3.9.8、Docker Engine 29.5.2；镜像为 `mongo:7.0`、`postgres:16`、`redis:7.2`，实际镜像 ID 已归档。Redis 使用 AOF/`appendfsync always`，恢复结论限于正常停止/启动。

证据目录为 `target/repository-driver-verify/3de4b70d9f6e456b8a1770fdf2687ddf/`，包含 `images.json.log`、`resilience.log`、各后端及 resilience 的独立报告目录、四份 `*-summary.xml`、`postgresql-sessions.log` 和 `cleanup.log`。脚本显式设置 Failsafe 总摘要位置并检查执行数，避免摘要被下一轮覆盖或无用例执行仍被判为成功。Compose 清理成功，随后查询确认本轮容器、网络和带项目标签卷均为 0；前几次排错运行的专用资源也已清理。

## 12. Kafka/Nacos 真实外部验证（2026-09-12）

新增专用隔离入口：

```powershell
pwsh scripts/VerifyKafkaNacosExternal.ps1 -Plan
pwsh scripts/VerifyKafkaNacosExternal.ps1
pwsh scripts/VerifyKafkaNacosExternal.ps1 -Resilience
```

验证 Compose 使用 `apache/kafka:3.9.1` 与 `nacos/nacos-server:v3.2.2`，每轮随机绑定 loopback 端口、唯一 Compose project，并在 `finally` 中清理容器、网络和卷。正常路径实际执行 Kafka RPC、Nacos 注册/订阅/注销以及 Kafka+Nacos 相关外部测试；恢复路径对 Kafka 和 Nacos 进行受控 restart，等待健康后重复外部测试。

最新成功摘要：

```text
kafka-nacos-external=ok|crossProcessJvm=true|multiNode=false|resilience=True|containersRemoved=true|networksRemoved=true|outputDir=L:\zero-server\target\kafka-nacos-external\df4a1e9fa4944a31888f59c81b0e14b0
```

机器可读证据包括 `images.json.log`、`containers.log`、`kafka.log`、`nacos.log`、`kafka-restart.log`、`nacos-restart.log`、恢复日志、容器日志和 `cleanup.log`。本轮证实单节点 Kafka/Nacos 基础设施承载多 JVM（provider JVM + caller/test JVM）跨进程 Actor RPC，并可在受控停止/启动后重新执行外部契约；`multiNode=false` 明确表示仍未执行多 broker/多 Nacos server 集群。

第 12 节记录 Kafka/Nacos 外部验证。

### 13. 多 broker / 多 Nacos 集群验证（2026-09-12，已通过基础故障矩阵）

新增入口：

```powershell
pwsh scripts/VerifyKafkaNacosCluster.ps1 -Plan
pwsh scripts/VerifyKafkaNacosCluster.ps1
pwsh scripts/VerifyKafkaNacosCluster.ps1 -Partition
```

本轮修复了 Nacos 3.2.2 集群的实际启动前提：从目标镜像导出并固定 `mysql-schema.sql`，由 MySQL 空数据卷首次初始化；两个 Nacos 节点均使用 `MODE=cluster`、相同 `NACOS_SERVERS` 和共享 MySQL。机器日志已确认两个节点分别输出 `Nacos started successfully in cluster mode with external storage`，Kafka 三 broker 也完成 KRaft 启动。

证据目录：

- 正常路径、broker 重启、Nacos 节点重启：`target/kafka-nacos-cluster/a9e4f1bf89014957a1ee7a48810aecc3`
- 网络断开/重连及恢复：`target/kafka-nacos-cluster/37c31033178c4ac481c1f3f958a26b5c`

两轮均生成机器可读 marker：

```text
kafka-nacos-cluster=ok|crossProcessJvm=true|multiNode=true|partition=False|containersRemoved=true|networksRemoved=true
kafka-nacos-cluster=ok|crossProcessJvm=true|multiNode=true|partition=True|containersRemoved=true|networksRemoved=true
```

验证实际覆盖三 broker Kafka、两个 Nacos 集群节点、共享 MySQL schema、跨 JVM Kafka/Nacos 外部测试、broker-1 重启、nacos-1 重启，以及 broker 网络断开/恢复。日志中可见 Kafka consumer restart/pending 状态变化和 Nacos gRPC 连接关闭/恢复事件。该证据证明了本地明文测试拓扑下的基础故障矩阵，但不等同于生产 SLA 或安全认证证明。

以下能力仍为 `not-proven`，并继续保持 `productionReady=false`、`goalAchieved=false`：TLS/真实鉴权、生产容量与 p99、长稳和灾备/RPO/RTO。

以下为本轮完成后的建议工作，尚未执行；继续以可组合、可替换和可重复接入为目标。

| 顺序 | 工作 | 验收要求 |
| --- | --- | --- |
| 1 | 运行中网络故障与写入结果确认 | 在独立代理或网络中注入连接重置、请求黑洞及响应丢失；分别验证读超时、写入结果未知、恢复后对账，明确业务幂等键/去重责任和禁止盲重试的边界；检查操作超时与启动预算的配置职责 |
| 2 | 延长运行与资源趋势 | 先 30 分钟，再至少 2 小时；采集 JVM 堆、线程、文件句柄、连接池活动/空闲/等待数及服务端资源，每个阶段核对成功数、余额和版本；穿插 runtime 创建/关闭，检查资源能回落；为 Redis journal 增长记录持久化和保留策略需求 |
| 3 | 容量、背压及独立环境复验 | 明确目标吞吐、延迟和资源预算后逐级提高负载，记录池满和执行域饱和时的错误/等待行为；将相同独立入口接入可手动触发的 CI 作业，归档版本、配置及报告，并保持默认本地 full 不依赖外部服务 |

先处理第 1 项的故障语义，再根据观测完善第 2、3 项。当前结论覆盖正常停启、有限并发和短期持续运行，不能替代强杀/断电恢复、生产容量或长稳结论。
