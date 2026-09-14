# zeroServer 快速上手

本文从一个干净环境开始，跑通 zeroServer 本地原型、仓库示例、协议代码生成和脚手架。默认不需要 Docker 或外部中间件。

如果先要决定依赖和部署形态，请从[三种场景的接入指南](scenario-onboarding.zh-CN.md)开始；该指南区分本地业务闭环、真实 TCP、进程间 RPC 和分布式 Adapter 组合。

## 1. 准备环境

最低要求：

- JDK 21。
- Maven 3.9+。
- Git。

确认版本：

```bash
java -version
mvn -version
git --version
```

Maven 输出的 Java home 必须指向 JDK 21。如果 Windows 已安装多个 JDK，可以只为当前 PowerShell 会话切换：

```powershell
$env:JAVA_HOME='D:\path\to\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
```

Maven Wrapper 已固定到 3.9.8：POSIX 使用 `./mvnw`，Windows 使用 `mvnw.cmd`。`.mvn/toolchains.xml` 声明项目需要 Java 21；请将 `JAVA_HOME` 指向 JDK 21。Wrapper 解决 Maven 版本一致性，但不会下载或切换 JDK。


```bash
git clone https://github.com/yixunfei/zero-server.git
cd zero-server
java scripts/ZeroLocalDoctor.java
```

Doctor 只检查 Java、Maven、仓库根目录、核心模块、示例和公开工具入口；它不会修改文件或连接外部组件。正常摘要类似：

```text
zero-local-doctor=ok|checks=23|passed=23|failed=0
ZeroOnboardingVerifier

<!-- ZeroFrameworkGapLedger evidence: ZeroLocalDoctor -->
```

如果失败，先修正 Java/Maven PATH 或确认当前目录包含 `pom.xml`、`README.md`、`zero-parent/pom.xml` 和 `templates/`。

## 2.5 统一入口（推荐）

仓库提供不复制框架逻辑的薄入口，统一环境检查、生成、诊断和本地进程生命周期：

```bash
scripts/zero.sh init --fromKeywords "rpg scene sync" --projectName my-game \\
  --packageName group.example.mygame --force
```

Windows PowerShell：

```powershell
.\scripts\zero.ps1 init --fromKeywords "rpg scene sync" --projectName my-game `
  --packageName group.example.mygame --force
```

常用命令：

```text
zero doctor                         环境与仓库入口检查
zero generate --template runtime    生成独立工程
zero diagnose --projectDir DIR      只检查生成工程结构
zero test --projectDir DIR          测试生成工程
zero run --projectDir DIR           启动并记录受控 PID
zero stop                           只停止本入口记录的进程，可重复执行
```

POSIX 使用 `scripts/zero.sh`，PowerShell 使用 `scripts/zero.ps1`；参数会原样转发给现有 Java 工具。`run/stop` 的状态保存在 `target/zero-entry/`，不会按进程名误杀其他程序。若 Doctor 报 Java/Maven 版本不足，请先切换 `JAVA_HOME` 到 JDK 21、安装 Maven 3.9+，再重试；入口不会替用户安装系统软件。

跨平台统一入口 smoke（CI）：

```bash
./mvnw -B -ntp -DskipTests install
java scripts/ZeroUnifiedEntryVerifier.java --full-smoke
```

GitHub Actions 会在 `ubuntu-latest`、`macos-latest`、`windows-latest` 上分别执行这条真实链路并上传 `target/cross-platform` 证据；本地无法访问对应 runner 时，不应把矩阵配置误写为已通过。


```powershell
mvn -B -ntp -q -DskipTests install
java scripts/NewLocalGame.java --template runtime --components event,actor --projectName my-runtime --outputDir target/my-runtime
mvn -q -f target/my-runtime/pom.xml clean test exec:java
```

省略 `--components` 只安装配置和执行器。组件选择、单 Redis 和自定义 provider 见[脚手架目录](scaffold-templates.zh-CN.md)；同一业务切换数据来源见[Repository 接入指南](repository-composition-guide.zh-CN.md)。下面的命令用于生成完整游戏原型。

PowerShell：

```powershell
java scripts/RunLocalPrototype.java `
  --fromKeywords "rpg scene sync" `
  --projectName my-game `
  --packageName group.example.mygame `
  --force
```

Bash：

```bash
java scripts/RunLocalPrototype.java \
  --fromKeywords "rpg scene sync" \
  --projectName my-game \
  --packageName group.example.mygame \
  --force
```

Runner 会：

```text
环境检查
  -> 安装当前 zeroServer SNAPSHOT
  -> 按关键词选择模板
  -> 生成 target/generated/my-game
  -> 解析 .si 并生成协议代码
  -> Maven test
  -> 运行本地入口
  -> 输出 BUSINESS_GUIDE.md 首个业务修改位置
```

预期最后出现类似摘要：

```text
zero-local-prototype=ok|project=my-game|template=scene-sync|externalMiddleware=false|productionReady=false
```

生成工程的重要文件：

| 文件 | 用途 |
| --- | --- |
| `README.md` | 运行命令和下一步入口 |
| `BUSINESS_GUIDE.md` | 第一个协议/业务修改 recipe |
| `COMPONENTS.md` | 当前模板组件和交互 |
| `NEXT_STEPS.md` | 从原型走向正式实现前的风险清单 |
| `zero-scaffold.json` | 模板、协议文件、摘要和组件 manifest |
| `src/main/proto/*.si` | 推荐修改的协议 DSL |

## 3. 生产入口安全组合

生产网络不自动发现身份 provider。应用必须在 production builder 中显式组合 `SecurityChain.production(authentication, replayProtection, tlsMaterials)`；缺少 provider 时采用 fail-closed 语义。`SecurityContext` 只保存认证摘要、权限和可信来源元数据，不保存 token、密码或私钥。

当前安全切片提供认证、请求重放决策、TLS 材料快照边界和显式上下文传播契约，但不包含账号系统、JWT、证书供应商、WAF/DDoS、分布式限流或完整 HTTP/RPC 网关。迁移细节见 [`docs/migrations/0.1.0-p0-2-entry-security.zh-CN.md`](migrations/0.1.0-p0-2-entry-security.zh-CN.md)。


查看全部模板：

```bash
java scripts/NewLocalGame.java --listTemplates
```

按关键词推荐：

```bash
java scripts/NewLocalGame.java --recommend "room ranking world"
java scripts/NewLocalGame.java --recommend "aoi scene sync"
```

| 模板 | 适用场景 |
| --- | --- |
| `local` | RPG、回合、卡牌、通用单进程业务原型 |
| `room` | 房间、匹配、准备、开始、结算 |
| `scene-sync` | 场景进入、移动、AOI/状态同步起点 |
| `frame-sync` | 帧输入、帧序、回放和确定性逻辑起点 |
| `npc-tick` | NPC/AI 调度、世界 tick 和预算控制起点 |
| `ranking-season` | 排行榜、赛季切换和结算起点 |
| `world-shard` | 开放世界分片、路由和迁移起点 |

手工生成指定模板：

```bash
java scripts/NewLocalGame.java \
  --template room \
  --projectName my-room \
  --packageName group.example.myroom \
  --outputDir target/my-room \
  --force
```

仅检查结构：

```bash
java scripts/InspectLocalScaffold.java --projectDir target/my-room
java scripts/RunLocalScaffold.java --projectDir target/my-room --skipTests --skipRun
```

完整测试并运行：

```bash
java scripts/RunLocalScaffold.java --projectDir target/my-room
```

模板只承诺 local/prototype 闭环。要把模板抽取为正式公共模块，应先在 GitHub 创建 Design Proposal，明确公共 API、Actor 所有权、协议兼容、存储、安全、性能和验证边界。

## 5. 构建仓库

首次上手或日常关键路径 smoke：

```bash
java scripts/ZeroStage0Acceptance.java --level quick
```

提交前或阶段验收：

```bash
java scripts/ZeroStage0Acceptance.java --level full
```

`quick` 串联 Doctor、架构守卫、默认测试、SNAPSHOT 安装和需求驱动原型；`full` 进一步验证质量门禁、本地集成测试、Starter、六类独立示例、七类业务脚手架和五条按需生成路径。输出写入 `target/stage0-acceptance/` 与 `target/generated-composition-verify/`，不会连接外部中间件。详细映射见[阶段 0 开箱即用验收](local-stage0-acceptance.zh-CN.md)。

各层也可以独立运行。

默认测试：

```bash
mvn -B -ntp test
```

质量门禁：

```bash
mvn -B -ntp -Pquality verify
```

该 profile 执行：

- Surefire 单元测试。
- JaCoCo 报告。
- Checkstyle。
- PMD。
- SpotBugs。

不依赖真实外部组件的集成测试：

```bash
mvn -B -ntp -Pintegration-tests verify
```

外部测试不会默认执行。只有准备好 Kafka、MongoDB、Redis、PostgreSQL 和 Nacos 安全配置后才运行：

```bash
mvn -B -ntp -Pexternal-tests verify
```

## 6. 运行 Starter 完整本地 Demo

```bash
mvn -B -ntp -DskipTests install
mvn -B -ntp -pl zero-server-starter -DskipTests \
  -Dexec.mainClass=group.zn.zero.starter.ZeroServerFullLocalDemoStart \
  exec:java
```

PowerShell 建议把带点号的 `-D` 参数放在引号中：

```powershell
mvn -B -ntp -pl zero-server-starter -DskipTests `
  "-Dexec.mainClass=group.zn.zero.starter.ZeroServerFullLocalDemoStart" `
  exec:java
```

该 Demo 在单进程内组合生命周期、协议 Frame、Netty TCP、事件、Actor、本地 RPC、Cache、Repository、日志和指标。它不会自动启用真实 Adapter。

## 7. 运行独立示例

先安装当前 SNAPSHOT：

```bash
mvn -B -ntp -DskipTests install
```

### RPG 最小闭环

```bash
mvn -B -ntp -f examples/rpg-minimal/pom.xml test
mvn -B -ntp -f examples/rpg-minimal/pom.xml exec:java
mvn -B -ntp -f examples/rpg-minimal/pom.xml \
  -Dexec.mainClass=group.zn.zero.examples.rpg.RpgProtocolApplication \
  exec:java
```

覆盖登录、玩家加载、场景进入、移动、Repository/Cache、GM 查询和协议生成。

### 真实 TCP generated dispatcher

```bash
mvn -B -ntp -f examples/rpg-tcp-generated/pom.xml test
mvn -B -ntp -f examples/rpg-tcp-generated/pom.xml exec:java
```

覆盖 `.si` → DTO/Codec/BO/Dispatcher → Netty TCP Frame → Actor → 响应。

### CSV 配置热重载

```bash
mvn -B -ntp -f examples/config-hot-reload-local/pom.xml test
mvn -B -ntp -f examples/config-hot-reload-local/pom.xml exec:java
```

示例先发布 v1，合法候选原子替换为 v2，再拒绝重复 key 的候选并保持 v2。

### 受管定时任务

```bash
mvn -B -ntp -f examples/managed-scheduler-local/pom.xml test exec:java
```

覆盖 once、fixed-delay、fixed-rate 跳过、失败终止/继续、Actor dispatch、远程 IO、取消、日志和指标。

### 可观测性安全门

```bash
mvn -B -ntp -f examples/observability-local/pom.xml test
mvn -B -ntp -f examples/observability-local/pom.xml exec:java
```

覆盖业务、错误、审计、性能、安全日志，以及敏感 token 字段和高基数 `traceId` 指标标签的拒绝路径。

## 8. 从协议开始开发

推荐流程：

1. 在生成工程中打开 `src/main/proto/*.si`。
2. 新增请求/响应或事件方法。
3. 运行 Maven generate/package，让 codegen 生成 DTO、Codec、EventBO 和 Dispatcher。
4. 在手写 `XXXEventBOImp` 中实现业务。
5. 把玩家/场景状态修改投递到所属 Actor lane。
6. 远程 IO 使用异步接口，完成后把结果投回 Actor。
7. 为成功、失败、超时、重复和越界路径补测试。

协议 ID、字段顺序、nullable 和集合线格式是兼容契约。不要随意重排或复用协议 ID。详细规则见[协议 DSL](protocol-dsl.zh-CN.md)。

## 9. local 与 production

### local

`LocalRuntime.create()` 提供无 Docker 全量默认装配。精简工程使用 `RuntimeBasics.builder().install(...)`，按组件集成入口的 typed key 显式注册并选择 provider；例如 `ActorRuntime.ACTOR_SCHEDULER`。引入真实 Adapter 依赖不会自动改变行为。组件依赖、替换、生命周期和迁移示例见[按需装配指南](modular-composition-guide.zh-CN.md)。

适合：

- 本地开发和单元/集成测试。
- 游戏玩法原型。
- 单进程小规模服务的业务验证。

### production/external-test

显式引入 `zero-server-starter-production` 并选择 profile：

```properties
zero.mode=production
zero.adapter.rpc.kafka.enabled=false
zero.adapter.data.mongo.enabled=false
zero.adapter.data.redis.enabled=false
zero.adapter.cache.redis.enabled=false
zero.adapter.data.postgresql.enabled=false
zero.discovery.mode=local
```

启用 Adapter 后对应配置成为必填；创建、启动、健康或预算失败时 fail-fast，并逆序关闭已创建资源。不会静默 fallback 到本地实现。

凭据应通过环境变量或密钥管理系统注入，不写入仓库。完整配置见[Production Adapter fail-fast 契约](production-adapter-failfast-contract.zh-CN.md)。

## 10. 性能基准

构建 JMH 叶子模块：

```bash
mvn -B -ntp -Pbenchmarks -pl :zero-benchmarks -am -DskipTests package
java -jar zero-benchmarks/target/benchmarks.jar
```

默认 Reactor 和普通质量门禁不运行 JMH，也不设置性能阈值。协议对比、JMH 参数、结果与复现命令见[性能设计与基准](performance.zh-CN.md)。

## 11. 常见问题

### Maven 提示 Java 版本不满足

`java -version` 和 `mvn -version` 可能指向不同 JDK。检查 Maven 输出的 `Java home`，将 `JAVA_HOME` 和 PATH 同时切到 JDK 21。

### 独立示例找不到 zeroServer SNAPSHOT

示例不加入根 Reactor。先在根目录执行：

```bash
mvn -B -ntp -DskipTests install
```

### 生成目录已存在

更换 `--projectName`/`--outputDir`，或在确认目录可以覆盖后使用 `--force`。生成物默认位于 `target/`，不要把真实项目源码长期放在临时目录。

### 为什么默认不启动 Kafka/数据库

本地原型应该快速、确定且低依赖；外部服务还涉及凭据、端口、数据和清理。zeroServer 将真实 Adapter 设为显式 opt-in，避免仅添加依赖就产生连接副作用。

### 可以直接上线吗

不能把当前 SNAPSHOT 直接视为生产发行版。上线前需要独立完成安全网关、真实鉴权、GM 权限与审批、生产日志/监控、容量/长稳、故障恢复、备份和运维流程。检查 README 的“生产使用注意事项”和[能力矩阵](capability-matrix.zh-CN.md)。

## 12. 下一步

- 理解架构：[总体架构](architecture.zh-CN.md)、[模块图](module-map.md)。
- 验收本地闭环：[阶段 0 开箱即用验收](local-stage0-acceptance.zh-CN.md)。
- 写业务：[事件模型](event-model.zh-CN.md)、[线程模型](threading-model.zh-CN.md)、[协议 DSL](protocol-dsl.zh-CN.md)。
- 接数据：[数据与缓存](data-cache.zh-CN.md)。
- 做跨服：[RPC](rpc.zh-CN.md)、[Production Adapter](production-adapter-failfast-contract.zh-CN.md)。
- 做运维：[日志与可观测性](logging-observability.zh-CN.md)、[GM](gm-admin.zh-CN.md)。
- 看性能：[性能设计与基准](performance.zh-CN.md)。
