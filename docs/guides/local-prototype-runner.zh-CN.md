# zeroServer 本地原型一命令 Runner

本文说明 `scripts/RunLocalPrototype.java` 的定位、命令和边界。它服务于第一次上手时最直接的问题：

```text
如何用一个命令生成最小 runtime、按需加组件，并完成首次测试和运行？
```

## 1. 定位

`RunLocalPrototype` 是显式 opt-in 的本地原型生成并 smoke 工具。它串联当前已有能力：

```text
NewLocalGame（增量构建当前 codegen，再生成项目）
  -> 按生成 POM 安装所选框架依赖闭包（含 zero-bom/zero-parent）
  -> RunLocalScaffold
  -> InspectLocalScaffold
  -> generated project Wrapper clean test / exec:java
  -> BUSINESS_GUIDE.md / First Business Change
```

它会写入生成目录并运行 Maven，因此应由使用者显式启动；默认 CI 不会自动在任意业务输出目录执行该命令。
生成项目携带 Wrapper，在其目录内使用 `./mvnw`（Windows PowerShell 使用 `./mvnw.cmd`）。首次下载 Maven 和依赖需要网络，无需安装全局 Maven。

执行生成前会 fail-fast 检查：

- 当前 Java feature 至少为 21。
- 当前 Maven 至少为 3.9。
- 当前目录同时包含 `pom.xml`、`README.md`、`zero-parent/pom.xml` 和 `templates/`。

它不会：

- 创建正式 Maven 模块。
- 修改框架源码或模板内容。
- 修改 POM、依赖方向、公共 API、SPI、协议 DSL、线程模型、RPC、存储、缓存、GM 权限、日志字段或 ErrorCode。
- 启动 Docker 或连接 Kafka、MongoDB、Redis、PostgreSQL、Nacos、Prometheus、Grafana。
- 证明生产就绪或容量达标。
- 代表用户已经确认高风险变更。

## 2. 命令

最小 runtime，无需游戏业务模块：

```powershell
java scripts/RunLocalPrototype.java --template runtime --projectName my-runtime --packageName sample.runtime
```

自定义组件沿用同一条入口：

```powershell
java scripts/RunLocalPrototype.java --template runtime --components greeting --catalog examples/scaffold-catalog/greeting.catalog.json --projectName my-greeting --packageName sample.greeting
```

选择真实 Redis 时使用 `--template runtime --components redis`。默认运行只做诊断，提示缺失配置；按生成 README 配置 `ZERO_CONFIG_FILE` 后，在生成目录执行 `./mvnw.cmd '-Dexec.args=--start' exec:java` 才启动真实服务。`requiresExternalServices=true` 表示所选组件需要外部服务，`externalMiddleware=false` 表示默认 smoke 不连接它们。

按游戏业务关键词生成并 smoke：

```powershell
java scripts/RunLocalPrototype.java `
  --fromKeywords "open world shard" `
  --projectName my-world-game `
  --packageName group.zn.zero.generated.myworld
```

未显式传入 `--outputDir` 时，默认输出为 `target/generated/my-world-game`。

按指定模板生成并 smoke：

```powershell
java scripts/RunLocalPrototype.java `
  --template room `
  --projectName my-room-game `
  --packageName group.zn.zero.generated.myroom `
  --outputDir target\my-room-game
```

正常摘要类似：

```text
zero-local-prototype-runner=ok|template=world-shard|project=my-world-game|installed=true|tested=true|ran=true|externalMiddleware=false|requiresExternalServices=false|productionReady=false|requiresConfirmation=true
```

成功输出会在摘要前给出下一步业务交接：

```text
Next business step:
  read "<outputDir>/BUSINESS_GUIDE.md"
  follow the `First Business Change` recipe before adding broader systems
  keep this generated project local/prototype until formal promotion is confirmed
```

## 3. 轻量验证模式

如果只想验证生成和结构检查，可以跳过依赖安装、生成项目测试和运行。生成器仍会增量构建当前 codegen，避免执行旧 SNAPSHOT：

```powershell
java scripts/RunLocalPrototype.java `
  --fromKeywords "open world shard" `
  --projectName my-world-game `
  --packageName group.zn.zero.generated.myworld `
  --outputDir target\my-world-game `
  --force `
  --skipInstall `
  --skipTests `
  --skipRun
```

摘要类似：

```text
zero-local-prototype-runner=ok|template=world-shard|project=my-world-game|installed=false|tested=false|ran=false|externalMiddleware=false|requiresExternalServices=false|productionReady=false|requiresConfirmation=true
```

即使跳过 install、test 和 run，结构检查成功后仍会输出 `BUSINESS_GUIDE.md` 与 `First Business Change` 交接提示。

## 4. 参数说明

| 参数 | 说明 |
| --- | --- |
| `--fromKeywords <words>` | 按业务关键词选择匹配度最高的模板；不能和 `--template` 同时使用 |
| `--template <template>` | 指定模板；默认 `local` |
| `--components <ids>` | 选择组件，逗号分隔；最小工程使用 `--template runtime` |
| `--catalog <file>` | 显式加载可信自定义 catalog；简写 bindings 与 Java 工厂入口共用依赖校验 |
| `--projectName <name>` | 生成项目名 |
| `--packageName <package>` | 生成项目 Java 包名 |
| `--outputDir <path>` | 输出目录；默认 `target/generated/<projectName>` |
| `--zeroVersion <version>` | 生成项目依赖的 zeroServer 版本，默认 `0.1.2-SNAPSHOT` |
| `--force` | 对已有目录执行受 ownership 约束的更新；不绕过手工修改冲突或旧 manifest 迁移要求 |
| `--skipInstall` | 复用已安装的框架构件；源码变更后应去掉此参数重新安装所选依赖闭包 |
| `--skipTests` | 跳过生成项目 Maven `clean test`；若仍需运行，会先 compile |
| `--skipRun` | 跳过生成项目 Maven `exec:java` |

## 5. 推荐使用顺序

```text
ZeroLocalDoctor
  -> RunLocalPrototype --template runtime（或按场景选择模板）
  -> 阅读 <outputDir>/BUSINESS_GUIDE.md
```

如果 `RunLocalPrototype` 失败，先看 stderr 中的 `RUNNER-INVALID-ARGUMENT` 或 `RUNNER-ENVIRONMENT`，再按输出中的子命令单独排查：

- `java scripts/NewLocalGame.java ...`
- 输出中的 `./mvnw.cmd -pl <所选依赖> -am -DskipTests install`
- `java scripts/RunLocalScaffold.java --projectDir ...`

## 6. 不证明什么

`RunLocalPrototype` 不证明：

- 项目可生产部署。
- 真实 Adapter 可连接。
- 性能基线、压测或长稳完成。
- GM/RBAC、安全审计、审批流完成。
- 生产网络治理、连接鉴权、心跳、限流完成。
- 房间、AOI、帧同步、NPC、排行榜、开放世界正式 API 已冻结。

它只证明一个 local/prototype 项目可以从当前仓库生成，并按显式选项完成结构检查、测试和运行 smoke。
