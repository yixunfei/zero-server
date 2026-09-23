# zeroServer 快速上手

适用版本：`0.1.0-SNAPSHOT`。先跑通业务，再选择基础设施。默认原型无外部中间件；当前能力和限制见[能力矩阵](capability-matrix.zh-CN.md)。

## 1. 准备环境

需要 JDK 21、Maven 3.9+ 和 Git。以下命令均在仓库根目录执行，单行命令可用于 Bash 或 PowerShell。

```bash
git clone https://github.com/yixunfei/zero-server.git
cd zero-server
java -version
mvn -version
java scripts/ZeroLocalDoctor.java
```

`java` 与 Maven 的 Java home 都应指向 JDK 21。Windows 多 JDK 环境可只切换当前 PowerShell 会话：

```powershell
$env:JAVA_HOME='D:\path\to\jdk21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

Doctor 检查环境、模块和公开入口，不修改文件、不连接中间件。成功摘要包含 `zero-local-doctor=ok`。Wrapper 固定 Maven 3.9.8：POSIX 使用 `./mvnw`，PowerShell 使用 `.\mvnw.cmd`；它不会安装或切换 JDK，Java Runner 仍需要 PATH 上可用的 Maven。

<!-- ZeroFrameworkGapLedger evidence: ZeroLocalDoctor -->

## 2. 选择场景

| 需求 | 选择 | 运行边界 |
| --- | --- | --- |
| 写玩家、场景业务原型 | `local` 或关键词 Runner | 生成协议、BO、登录和移动，本地执行后退出 |
| 空白内核或只要事件/Actor | `runtime`，可选 `--components event,actor` | 最小 core/runtime/bootstrap 三个框架制品，无网络或数据库 |
| 验证中心—逻辑接口 | `examples/modular-composition/center-logic` | 一个进程内完成 direct/in-memory RPC 调用；不作为分布式证据 |
| 验证双进程 Kafka | `examples/modular-composition/center-logic-kafka` | 独立 common-contract、center JVM、logic JVM；需要隔离 Kafka broker 和外部验收证据 |
| 拆分中心与逻辑进程 | `runtime --components kafka` | 只引入所需传输，需配置 Kafka 并接入业务 |
| 组合分布式基础设施 | `runtime` 按需选择 Adapter | 先诊断，显式启动；完整治理仍需应用完善 |

`zero-server-starter` 和 Production Starter 是全量便利组合。精简应用可用独立集成模块；首次全仓安装是准备本地 Maven 工件，不会使生成应用依赖所有模块。

脚手架进程退出码固定为：`0` 成功，`1` 普通生成失败，`2` 参数解析失败，`3` plan/diff 或升级被阻塞。失败诊断写入 stderr，格式为 `ERROR_CODE|message`；stdout 不承载失败诊断。可用 `java scripts/ZeroAcceptanceEvidence.java --no-stage0` 执行独立子进程矩阵，并在 `target/acceptance-evidence/cli-exit-matrix/` 查看原始 stdout/stderr 与退出码证据。


## 3. 生成并运行业务原型

最快的完整流程：

```bash
java scripts/RunLocalPrototype.java --fromKeywords "rpg scene sync" --projectName my-game --packageName group.example.mygame
```

Runner 检查环境、安装 SNAPSHOT、生成 `target/generated/my-game`、生成协议、执行测试和业务流程。成功摘要包含 `zero-local-prototype=ok`。默认是一次本地验证，结束后关闭资源。

从生成工程开始写业务：

| 文件 | 修改内容 |
| --- | --- |
| `BUSINESS_GUIDE.md` | 第一个业务修改示例和运行步骤 |
| `src/main/protocol/*.si` | 请求、响应、事件方法；不要复用已有协议 ID |
| 手写 `XXXEventBOImp` | 业务实现；玩家/场景状态修改投递到所属 Actor |
| `COMPONENTS.md`、`zero-scaffold.json` | 已选组件和生成清单 |

选择指定模板或空白内核时，先安装，再生成：

```bash
mvn -B -ntp -DskipTests install
java scripts/NewLocalGame.java --template runtime --components event,actor --projectName my-runtime --outputDir target/my-runtime
mvn -q -f target/my-runtime/pom.xml clean test exec:java
```

省略 `--components` 得到空白 runtime；使用 `--template local` 得到玩家/场景原型。七种业务模板、外部组件和参数详见[模板目录](scaffold-templates.zh-CN.md)，完整 Runner 选项见[原型 Runner](guides/local-prototype-runner.zh-CN.md)。

需要验证客户端真实发包时，运行：

```bash
mvn -q -f examples/rpg-tcp-generated/pom.xml clean test exec:java
```

TCP 示例监听本机临时端口，执行 Socket → Netty → 生成 BO → 响应后退出。

当前 `local + net` 已生成 Server/客户端辅助类，但 **2026-09-18 新生成工程编译验证失败**：装配代码调用无参 `NetworkRuntime.module()`，现有 API 要求显式 policy/limiter。以下命令用于复现该限制，暂不作为开箱即用入口；真实 TCP 收发请使用上面的 `rpg-tcp-generated` 示例。

```bash
java scripts/NewLocalGame.java --template local --components net --projectName tcp-demo --packageName group.example.tcpdemo --outputDir target/tcp-demo
mvn -q -f target/tcp-demo/pom.xml clean test
```

Server 源码定义了 `--port`、`--once` 和关闭流程，客户端类是没有独立 main 的 smoke 辅助类；这些入口在装配编译问题解决前不能由上述新工程运行。响应设计仍是请求 DTO 的最小回显。认证、TLS、心跳、限流和断线重连需应用接入，见[网络完整链路](guides/net-full-flow.zh-CN.md)及[本轮核对报告](reports/documentation-audit-20260918.zh-CN.md)。

## 4. 中心—逻辑服务器

完成根目录安装后，先运行无需中间件的接口调用：

```bash
mvn -q -f examples/modular-composition/center-logic/pom.xml clean test exec:java
```

示例中 `CenterRpc` 是共享接口，中心实现 `CenterService`，逻辑侧通过 `RpcClientFactory` 调用，输出 `heartbeats=1`。这是单进程示例，便于先开发业务契约。

已有[共享契约、center 与 logic 三模块 Kafka 示例](../examples/modular-composition/center-logic-kafka/README.md)，可作为双进程接线起点；真实 broker 验收需另行执行，不能由本地编译通过推断。

需要自行组装时，分别生成工程：

```bash
java scripts/NewLocalGame.java --template runtime --components kafka --projectName center --outputDir target/center
java scripts/NewLocalGame.java --template runtime --components kafka --projectName logic --outputDir target/logic
mvn -q -f target/center/pom.xml clean test exec:java
mvn -q -f target/logic/pom.xml clean test exec:java
```

默认只诊断配置，不连接 Kafka。将共享接口和各自业务放入工程，用 `RpcServiceBinder` 绑定服务，通过 `RpcRuntime.RPC_HANDLER_REGISTRY` / `RPC_TRANSPORT` 取得所选实现。两进程使用不同的 `client-id`、`consumer-group-id` 和 `reply-topic`，请求 topic 路由保持一致。

静态部署不需要 Nacos；选择 `nacos` 后仍须显式注册实例和绑定 resolver。接线细节见[RPC 设计](rpc.zh-CN.md)与 [Kafka 使用指南](../zero-rpc-kafka/USER-GUIDE.zh-CN.md)。目前远程 RPC Adapter 为 Kafka，免 broker 的轻量双进程方案在[路线图](optimization-roadmap.zh-CN.md)中。

## 5. 按需接入外部基础设施

以下演示全部可选 Adapter，可从 `--components` 中删去不需要的项：

```bash
java scripts/NewLocalGame.java --template runtime --components kafka,nacos,mongo,redis,postgresql --projectName services --outputDir target/services
mvn -q -f target/services/pom.xml clean test exec:java
```

生成工程包含所选依赖、`RuntimeAssembly.java` 和 `config/application.properties.example`。把配置样例复制到自己管理的文件，填写所选服务的地址与认证信息，再指定绝对路径。例如 PowerShell：

```powershell
$env:ZERO_CONFIG_FILE='D:\my-game-config\services.properties'
mvn -q -f target/services/pom.xml exec:java '-Dexec.args=--diagnose'
mvn -q -f target/services/pom.xml exec:java '-Dexec.args=--start'
```

`runtime-diagnosis=incomplete` 表示缺配置，`ok` 表示配置和组件图有效；两者都不证明连通。`--start` 创建 Adapter、执行启动健康检查，然后关闭本次验证的资源。上述无连接的默认行为适用于 `runtime` 模板；业务模板额外选择外部 Adapter 后，需要真实配置和服务。

配置 `zero.mode` 支持 `standalone`、`external-test`、`production`，外部模板默认 `external-test`。档位本身不会自动启用中间件。

组合规则：

- `kafka` 替换本地 `rpc`，`nacos` 替换本地 `discovery`；无需手工排除被替换的 provider。
- `redis` 选择 Redis 数据来源；同时选择 `cache` 仍是本地缓存。Redis L2 需显式 codec 和 cache 配置。
- 单数据来源默认提供 `main`；多来源按 `local`、`redis`、`mongo`、`postgresql` 命名。业务角色绑定见 [Repository 指南](guides/repository-composition-guide.zh-CN.md)。
- 业务对象通过构造器接收 Repository、RPC 接口、Actor 或 EventBus，装配入口负责选择实现。

手工 Maven/Java 接入、typed 配置与 provider 替换见[按需装配](guides/modular-composition-guide.zh-CN.md)。`runtime + net` 只生成网络运行时策略依赖与配置，不创建 listener；`local + net` 另生成上面的 Server 与 TcpClient 类。手工接入长驻 TCP 时显式装配 `ServerFactory.tcp`、业务 executor 与 `ZeroServerTcpApplication`，按 `start → probe → stop` 管理生命周期；`127.0.0.1:0` 仅用于本地测试，`runDemo`/smoke 输出不等于长驻网络服务或生产就绪。安全接线见[生产网络契约](reference/production-network-lifecycle-contract.zh-CN.md)。

## 6. 统一命令和验证

POSIX 使用 `scripts/zero.sh`，PowerShell 使用 `.\scripts\zero.ps1`：

| 子命令 | 用途 |
| --- | --- |
| `doctor` | 检查环境与入口 |
| `init --fromKeywords "rpg scene sync" --projectName my-game` | 执行上面的完整原型流程 |
| `generate --template runtime --projectName my-runtime` | 生成工程 |
| `diagnose --projectDir DIR` | 检查工程结构；runtime 模板的组件诊断直接用 Maven `--diagnose` |
| `test --projectDir DIR` | 测试生成工程 |
| `run --projectDir DIR` / `stop` | 启动并记录受管进程、停止该进程；不会让一次性示例自动变成长驻服务 |

本地测试用 `mvn -B -ntp test`。需要验证框架改动时用 `java scripts/ZeroStage0Acceptance.java --level quick` 或 `--level full`，层级和范围见 [Stage 0 验收](operations/local-stage0-acceptance.zh-CN.md)。所有可运行示例集中在[示例索引](../examples/README.md)。

## 7. 常见问题

| 问题 | 处理 |
| --- | --- |
| Java 版本不满足 | 同时检查 `java -version` 和 `mvn -version`，修正 `JAVA_HOME` 与 PATH |
| 示例找不到 SNAPSHOT | 在根目录执行 `mvn -B -ntp -DskipTests install` |
| 生成目录已存在 | 新建工程使用新目录；升级先用 `--plan`/`--diff`，确认后 `--apply`；`--force` 不绕过 ownership 冲突。长期业务源码应移出 `target/` |
| Adapter 配置不完整 | 根据生成的配置样例和 `missingConfigKeys` 补齐后再次诊断 |
| 想部署正式服务 | 按[能力矩阵](capability-matrix.zh-CN.md)核对安全、恢复、持久化和容量缺口，再参考[部署基线](operations/deployment-baseline.zh-CN.md) |

后续查阅从[文档总览](README.md)进入；已有项目升级参见[迁移说明](migrations/README.md)。
