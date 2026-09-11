# 按需组装能力审核与后续计划

审核日期：2026-09-07。后续已按用户确认的顺序实施，进度与验证见第 5 节；第 1–4 节保留审核时的发现与计划。

用户已确认后续优先巩固“按需组装、实现可替换、开箱即用”。本轮针对此前三批改造后的当前工作区进行复查，覆盖 Runtime 组装、Repository 数据源绑定、脚手架生成与相关验收入口。工作区包含尚未提交的改造，审核对象不是单个提交，也不代表对全部业务模块完成了穷尽审核。

## 1. 审核发现

发现五项 P2 问题，按影响排序。均有本地复现；MongoDB 项仅验证集合名称映射，没有连接数据库或观察到跨命名空间数据泄漏。

### R1：MongoDB 不同逻辑名称会映射到相同物理集合

- 位置：[MongoDriverEnvelopeStore.java](../../zero-data-mongo/src/main/java/group/zn/zero/data/mongo/MongoDriverEnvelopeStore.java)，第 194–200 行。
- 触发：在同一数据库中创建 `tenant-a/balances` 和 `tenant_a/balances` 两个 Repository。
- 原因：名称段将非字母、数字、下划线字符统一替换成 `_`，再用 `__` 拼接。两个逻辑名称都生成 `tenant_a__balances`；分隔符本身也可能引入歧义。
- 影响：Repository 工厂按原始逻辑名称检查冲突，因此接受这两个定义。但 Mongo 文档仅以编码后的实体 ID 作为 `_id`，同 ID 会竞争同一个集合的唯一键，无法兑现两个逻辑存储空间的独立性。查询仍包含 namespace/collection 条件，不能据此宣称已发生数据串读。
- 证据：以代理 Mongo client/database 记录 `getCollection()` 调用，工厂接受两个定义，得到 `[tenant_a__balances, tenant_a__balances]`，不同物理名称数量为 `1`。
- 修复方向：先明确逻辑名称到物理名称的一一对应规则，或在创建时明确拒绝冲突。覆盖 `-` 与 `_`、分隔符歧义、相同实体 ID 等边界。变更物理名称前明确已有开发数据的处理方式，不默认新增兼容层。

### R2：网络组件在执行器替换生效前校验旧值

- 位置：[ProductionNetworkProvider.java](../../zero-runtime-net/src/main/java/group/zn/zero/runtime/net/ProductionNetworkProvider.java)，第 125 行；组装时序见 [ProductionAssembly.java](../../zero-runtime-production/src/main/java/group/zn/zero/runtime/production/ProductionAssembly.java)。
- 触发：启用网络组件，使用默认 `ProductionAssembly.builder(config)`，随后通过 `configure(c -> c.replace(RuntimeBasics.EXECUTORS, managedExecutors))` 提供合法的非内联执行器。
- 原因：production 模块解析阶段仍读取 builder 最初捕获的默认 direct 执行器，此时 composition 定制尚未应用。
- 影响：合法实现替换在 `CONFIG_SELECTION` 阶段被拒绝，错误键为 `builder.executors.remoteIo`。同一个 managed 执行器通过三参数 builder 传入则组装成功，公共替换入口的行为不一致。
- 证据：替换入口失败；构造入口对照组成功，且 runtime 获取到的执行器与传入实例相同。均未启动网络监听。
- 修复方向：校验最终被选择的执行器，保持非内联要求，并保留延迟创建与资源回滚语义。当前 `create()` 在第 149–154 行会再次校验实际执行器，因此本项是误拒绝有效配置，不是绕过安全约束。

### R3：脚手架接受不能编译的 Java 名称并报告生成成功

- 位置：[ProjectScaffoldRequest.java](../../zero-codegen/src/main/java/group/zn/zero/codegen/scaffold/ProjectScaffoldRequest.java)，第 28–32 行。
- 触发一：`--projectName 123-game --packageName group.example.valid`，生成 `123GameApplication.java`，随后 Maven 编译失败。
- 触发二：`--projectName review-package --packageName group.class.game`，包名含 Java 保留字，生成成功后编译失败。
- 原因：项目名字符正则未约束派生类名；包名正则只检查字符形态，没有按 Java 语言规则检查关键字。
- 影响：用户得到“创建成功”提示，却无法直接构建生成工程，破坏开箱即用流程。
- 修复方向：区分 Maven artifact 名与 Java 类型名，使用 JDK 标识符/关键字校验能力检查最终生成名称，在创建文件前完成验证。通过公开 CLI 覆盖有效与无效输入，不只测试内部正则。

### R4：脚手架静默忽略未知参数，导致组件选择丢失

- 位置：[ProjectScaffoldCli.java](../../zero-codegen/src/main/java/group/zn/zero/codegen/scaffold/ProjectScaffoldCli.java)，第 224 行。
- 触发：把 `--components redis` 拼为 `--componets redis`。
- 原因：解析器将所有 `--key value` 放入 map，后续仅读取已知键，没有拒绝未知选项。
- 影响：命令退出码为 0，并报告创建成功，但实际只有默认 `bootstrap` 组件及 local profile；用户请求的 Redis 选择被静默丢弃。
- 证据：运行上述命令，检查 CLI 输出及生成清单，组件为 `bootstrap`。
- 修复方向：建立明确的选项集合并拒绝未知键；同时明确重复参数与别名冲突的规则，在写入输出目录前报错。

### R5：PostgreSQL 示例将必填表名写成可选配置

- 位置：[Repository 示例 README](../../examples/repository-composition/README.md)，第 21 行；实际约束见 [ProductionPostgresqlDataProvider.java](../../zero-runtime-postgresql/src/main/java/group/zn/zero/runtime/postgresql/ProductionPostgresqlDataProvider.java)，第 132–135、217、237–240 行。
- 触发：按 README 仅设置 PostgreSQL URL、用户名、密码，不提供 `ZERO_POSTGRESQL_TABLE`。
- 原因：README 将表名列为 optional，但示例使用的 production provider 要求四项配置全部存在。`PostgresqlDriverSettings.fromSystemProperties()` 的默认值不参与该解析路径。
- 影响：按文档给出的最小配置无法启用示例所需的数据源。
- 证据：仅提供前三项配置，`diagnose().missingConfigKeys()` 返回 `[zero.postgresql.table]`，未连接外部服务。
- 修复方向：优先使文档与现有显式配置要求一致，并验证文档声明的最小配置能够通过诊断。若希望提供默认表名，应单独明确该配置策略。

## 2. 验证记录与边界

### 本轮执行

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| 架构守卫 | 47 modules / 18 rules，0 violations | 当前工作区模块约束通过 |
| 定向单元测试 | 23 tests，0 failures / errors / skipped | 7 个测试类，覆盖组装、Repository 生命周期与脚手架 |
| 混合组件生成工程 | `clean test exec:java` 通过 | `data,redis,cache,custom-actor,discovery`，11 项 capability，`started=false` |
| 网络替换对照探针 | 有效替换被拒绝，构造参数入口成功 | 复现 R2，未启动网络 |
| Mongo 名称探针 | 两个逻辑名称落入同一集合名 | 复现 R1，使用代理，无数据库连接 |
| PostgreSQL 最小配置诊断 | 缺失 `zero.postgresql.table` | 复现 R5，无数据库连接 |
| 非法命名生成工程 | 生成成功，编译失败 | 复现 R3 的两个输入 |
| 拼错选项生成工程 | 退出码 0，默认 bootstrap | 复现 R4 |

定向测试类：`ScaffoldComponentsTest`、`ProjectScaffoldGeneratorTest`、`RuntimeBasicsTest`、`RuntimeCompositionTest`、`RepositoryCatalogTest`、`RepositoryScopeTest`、`ProductionRuntimeAssemblyContractTest`。

执行环境为 Java 21、Maven 3.9.8，通过仓库环境脚本切换。复现工程位于本地 `target/review-20260907/`，属于可清理的审核产物，不是正式回归测试。可用下列命令重新执行已有探针：

```powershell
. ./scripts/dev-env.ps1
java scripts/ZeroArchitectureGuard.java
mvn -B -ntp -q -pl zero-codegen,zero-data,zero-server-starter-production -am `
  '-Dtest=ScaffoldComponentsTest,ProjectScaffoldGeneratorTest,RuntimeBasicsTest,RuntimeCompositionTest,RepositoryCatalogTest,RepositoryScopeTest,ProductionRuntimeAssemblyContractTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -ntp -q -f target/review-20260907/probes/pom.xml compile exec:java
```

探针输出：

```text
network-replacement=rejected|phase=CONFIG_SELECTION|message=invalid production adapter config key: builder.executors.remoteIo
network-constructor=accepted|sameSelectedExecutor=true
postgres-required=[zero.postgresql.table]
mongo-physical-collections=[tenant_a__balances, tenant_a__balances]|distinct=1
```

### 历史验收与本轮未覆盖内容

此前三批改造的 full 验收为 17/17 通过，耗时 420935 ms；该次 Surefire 516 项，本地 Failsafe 1 项。随后 discovery 清单修正增加 1 项回归，文档记录当前测试总数为 517，并记录相关模块及生成消费者复验通过。详见 [本地验收记录](../local-stage0-acceptance.zh-CN.md) 与 [三批改造记录](../demand-driven-composition.zh-CN.md)。这些是历史证据，本轮没有重新执行 full，也没有重新执行全部 517 项测试。

本轮没有验证真实数据库读写、服务故障恢复、性能、独立 CI 环境或线上部署。现有通过项说明已覆盖的组装主流程成立，不能据此宣称所有实现都可无差别替换，或已达到生产可用。Repository 同步阻塞、资源关闭等待等已知约束继续按现有文档处理，未将其重复列为新缺陷。

## 3. 后续实施顺序

以下均为待实施计划。先关闭正确性问题，再扩大公开契约验收，随后验证真实实现，最后收敛接入体验。各阶段完成后更新验证记录；涉及公共行为的设计先复核再编码。

| 顺序 | 工作范围 | 完成标准 |
| --- | --- | --- |
| 1. 修复审核问题 | 依次处理 R1、R2、R3/R4、R5；集中在现有驱动映射、组装选择、CLI 边界与文档 | 五项复现转为正式回归且通过；既有非法执行器仍被拒绝；无效 CLI 输入不产生半成品；运行受影响模块测试、quality、架构守卫与生成消费者验收 |
| 2. 补齐公开契约验收 | 组件名称与别名、非法选择、混合组装、POM/manifest/runtime plan 一致性、替换/禁用及生命周期失败路径 | 每个公开组件选择都有覆盖；关键依赖交互有组合覆盖；实际最小 classpath 不夹带未选择驱动；未选择 provider 不创建资源；启动失败正确回滚；full 验收重新通过 |
| 3. 验证真实 Repository 实现 | 在明确的隔离目标上运行 local/MongoDB/PostgreSQL/Redis 的同一业务契约 | 相同业务代码通过 CRUD、CAS 冲突、跨命名空间同 ID、持久化后重建客户端读取、关闭后拒绝访问检查；记录配置、版本和清理结果；失败路径有可追溯证据 |
| 4. 收敛用户接入流程 | 整理 runtime 模板与现有 `RunLocalPrototype`、`InspectLocalScaffold` 的适用入口；完善组件目录、配置说明与数据源角色示例 | 从组件选择到生成、配置诊断、运行、替换实现形成可复现流程；全新生成消费者按文档完成接入；指南与生成产物一致 |

### 阶段 1 的设计检查点

1. Mongo 映射必须同时考虑名称字符、组合边界、驱动长度限制及现存开发数据；先定规则，再改实现，避免只修复一个示例输入。
2. 执行器校验必须面向实际选中项，覆盖直接注入、`replace` 与延迟 base provider；不能为了提前诊断而提前分配线程或连接。
3. CLI 的合法输入应有单一权威定义；Java 名称校验与派生类名的生成规则保持一致，避免另造一套逐渐偏离的正则。
4. 配置文档按实际解析路径验证，不把其他工厂入口的默认值误当成当前入口行为。

### 阶段 2 的覆盖策略

保留现有 capability model 与组件目录作为规则来源，避免在测试脚本或新 builder 中复制第二套依赖模型。测试关注公开行为：请求何种组件、生成何种依赖、实际选择何种实现、失败时释放何种资源。对单组件和重点组合分别覆盖，不机械枚举全部组合，也不以生成文本快照替代真实编译与运行。

### 阶段 3 的执行边界

先将隔离服务地址、数据库/表/键前缀、访问配置和执行命令落实为可检查的测试配置，再执行外部读写。每次使用唯一运行标识，只清理本轮创建的数据，不使用 Redis `flushDB` 等全库清理。真实验证作为显式集成入口，保留默认本地验收无需外部中间件的能力。性能和故障恢复的扩大验证在基础契约通过后另列工作，不以一次示例运行代替。

## 4. 本轮交付与建议

本轮仅新增审核报告与本地复现产物，未修复生产源码。建议从阶段 1 开始，先把五项边界问题和对应回归闭环，再继续扩展能力。后续暂不增加新的业务模板、驱动种类或通用抽象层，以现有模块的可组合性、实现替换一致性和接入可重复性作为验收重点。

## 5. 后续实施记录

用户确认“按推荐的顺序继续”后，已完成以下实现与验证。最终本地 full 验收为 17/17，失败和跳过均为 0，耗时 503608 ms。

| 阶段 | 当前状态 | 实施结果 |
| --- | --- | --- |
| 1. 五项问题修复 | 已完成 | Mongo 一一对应编码与长度检查；网络校验最终执行器并保留错误归因；Java 名称与 CLI 选项预校验；PostgreSQL 必填表名说明及最小配置回归；定向回归和 full 均通过 |
| 2. 公开组装契约 | 已完成 | 十二个公开组件选择逐项编译运行，另有五条代表路径和两种混合组装，共十九个消费者；结构化检查 JSON/POM；实际 runtime plan 对照 provider/capability；现有失败回滚与禁用测试及 full 均通过 |
| 3. 真实 Repository | 已通过 | local、MongoDB、PostgreSQL、Redis 共用业务契约；三个外部 Failsafe 用例分别 1/1，通过且无跳过；本轮容器已清理 |
| 4. 用户接入流程 | 已完成 | `RuntimeAssembly.diagnose(...)` 与 `create(...)` 共用组装定义；runtime CLI 新增 `--diagnose`；指南明确纯 runtime、业务原型及真实数据验证入口；全新消费者按指南生成、诊断和运行通过 |

### 修复与设计边界

- Mongo 集合采用 `z_<UTF-8 hex namespace>__<UTF-8 hex collection>`，字符与分隔边界无歧义。包括数据库名与点分隔符的完整 namespace 不超过 235 字节。非法 Unicode 与超长输入在选择集合前失败。已有开发数据需要显式迁移或重建，不读取旧格式作为回退。原 Mongo 外部测试也改为唯一 ID 与记录级清理，取消旧集合名硬编码删除。
- 网络校验移至 provider 创建阶段，实际选择的执行器仍必须非内联。诊断不调用延迟工厂，因此不会声称已验证未创建执行器的实例约束。错误仍归属 `network-lifecycle`，阶段为 `CONFIG_VALIDATION`。
- 脚手架使用 JDK Java 名称校验，派生类名生成与输入验证共用一处规则。选项统一归一化后检查未知键、重复键和别名冲突。仓库薄启动器只在未提供 `--templateRoot` 时补默认值。
- JSON 解析库仅为 codegen 测试依赖，不进入生成应用的运行依赖。验收读取 Maven 日志使用宿主编码，避免 Windows 中文错误信息被解码异常遮盖。
- 总验收入口移除旧的五个消费者数量硬编码，使用稳定成功标记；各消费者仍须通过实际编译、运行与契约检查，且子进程退出码必须为 0。

### 最终本地验收

```text
zero-stage0-acceptance=ok|level=full|checks=17|passed=17|failed=0|skipped=0|durationMs=503608|externalMiddleware=false|productionReady=false|outputDir=target/stage0-acceptance
generated-compositions=ok|consumers=19
```

Reactor Surefire 527 项，本地 Failsafe 1 项，失败、错误和跳过均为 0。Checkstyle、PMD、SpotBugs、JaCoCo 门禁通过；架构守卫 47 modules / 18 rules，0 violations / warnings。仓库非 target Java 文件 830 个，最大 1494 行，1500 行文件 / 100 行方法门禁通过。

全新接入工程位于 `target/composition-workflow-verify/`，选择 `event,custom-actor`，`clean test`、`--diagnose` 和实际启动均通过；诊断确认 `application.actor` 替换了默认 Actor provider。相关指南本地链接检查通过。默认 full 仍无需外部数据库，真实驱动验证作为单独证据记录如下。

### 可重复验证入口

```powershell
. ./scripts/dev-env.ps1
java scripts/ZeroStage0Acceptance.java --level full
./scripts/VerifyRepositoryDrivers.ps1 -Plan
./scripts/VerifyRepositoryDrivers.ps1
```

真实驱动运行输出目录：`target/repository-driver-verify/aa7a4abfdafd4cef80b28b5a35cee716/`。Docker Engine 29.5.2，镜像为 `mongo:7.0`、`postgres:16`、`redis:7.2`；镜像 ID 保存在 `images.json.log`。各外部摘要均为 completed=1、errors=0、failures=0、skipped=0。

业务验证覆盖 CRUD、旧版本 CAS 冲突、`contract-<run>` 与 `contract_<run>` 使用同一个实体 ID 的独立性、runtime 关闭后拒绝访问，以及真实实现重建客户端后读取已保存余额。测试清理各自唯一命名空间内的记录；编排脚本在 finally 清理唯一 Compose project 的容器、卷和网络。Docker 查询确认本轮 project 下无残留容器。

这次真实验证没有执行数据库进程重启、网络故障注入、并发压力或长稳测量。重建客户端读取不能替代服务端故障恢复验证。下一阶段应以这些运行约束为重点，不扩大驱动种类和业务模板。

## 6. 运行约束验证与连接复用修正

用户随后确认继续按主方案推进：停启恢复、并发 CAS、每种实现五分钟持续运行、更新验收记录。本阶段沿用既有四种实现与同一业务服务，默认本地验收仍无需外部数据库。

验证中修正了 Windows 下 Docker 参数引号解析及动态端口在重启后变化的问题。测试容器现在随机选取端口后固定整轮绑定，通过 Compose 标签和完整容器 ID 检查归属，并断言重启前后端点不变。失败时保留独立 Failsafe 报告和容器日志，清理仍仅针对本轮资源。

持续运行还复现了 PostgreSQL 按操作新建连接导致的 TCP 端点耗尽，Windows 系统事件 4227 与两次失败时间吻合。修正将存储的连接获取改为标准 `DataSource`，在 PostgreSQL runtime 集成模块中使用有界 HikariCP 池；默认最大 8，可通过模块参数调整。连接池依赖没有进入中立数据模块或其他集成模块，生成消费者增加 Hikari 类缺席检查。工厂失效先于池关闭，调用方自带数据源的关闭权仍归调用方；拒绝手动事务连接以免返回写入成功却未提交。

新增回归验证借用连接在查询失败后释放、拒绝不符合事务契约的数据源、池满等待上限、物理连接复用及资源登记顺序。外部检查保持 4 个独立客户端竞争同一版本，只重试明确拒绝的 CAS；连接错误和写入结果不确定时明确失败，不调低负载掩盖故障。

2026-09-08 最终复验已完成：本地 full 17/17，真实驱动基础契约、停启恢复、并发 CAS 及四种实现各五分钟持续运行均通过。本阶段最终运行标识、详细数量和后续工作统一维护在[主推进方案第 11 节](../demand-driven-composition.zh-CN.md#11-运行约束验证已完成)，避免两份报告再次出现状态漂移。前文各节的“未验证”描述属于对应历史验收时点。
