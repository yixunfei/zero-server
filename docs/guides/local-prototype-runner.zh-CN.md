# zeroServer 本地原型一命令 Runner

本文说明 `scripts/RunLocalPrototype.java` 的定位、命令和边界。它服务于第一次上手时最直接的问题：

```text
我能不能用一句需求关键词，直接生成一个本地游戏服务器原型，并跑一次 smoke？
```

## 1. 定位

`RunLocalPrototype` 是显式 opt-in 的本地原型生成并 smoke 工具。它串联当前已有能力：

```text
mvn -q -DskipTests install
  -> NewLocalGame
  -> RunLocalScaffold
  -> InspectLocalScaffold
  -> generated project Maven test / exec
  -> BUSINESS_GUIDE.md / First Business Change
```

它会写入生成目录并运行 Maven，因此应由使用者显式启动；默认 CI 不会自动在任意业务输出目录执行该命令。

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

按业务关键词生成并 smoke：

```powershell
java scripts/RunLocalPrototype.java `
  --fromKeywords "open world shard" `
  --projectName my-world-game `
  --packageName group.zn.zero.generated.myworld `
  --force
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
zero-local-prototype-runner=ok|template=world-shard|project=my-world-game|installed=true|tested=true|ran=true|externalMiddleware=false|productionReady=false|requiresConfirmation=true
```

成功输出会在摘要前给出下一步业务交接：

```text
Next business step:
  read "<outputDir>/BUSINESS_GUIDE.md"
  follow the `First Business Change` recipe before adding broader systems
  keep this generated project local/prototype until formal promotion is confirmed
```

## 3. 轻量验证模式

如果只想验证生成和结构检查，不想运行根仓库 install、生成项目 test 或 exec，可以使用：

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
zero-local-prototype-runner=ok|template=world-shard|project=my-world-game|installed=false|tested=false|ran=false|externalMiddleware=false|productionReady=false|requiresConfirmation=true
```

即使跳过 install、test 和 run，结构检查成功后仍会输出 `BUSINESS_GUIDE.md` 与 `First Business Change` 交接提示。

## 4. 参数说明

| 参数 | 说明 |
| --- | --- |
| `--fromKeywords <words>` | 按业务关键词选择匹配度最高的模板；不能和 `--template` 同时使用 |
| `--template <template>` | 指定模板；默认 `local` |
| `--projectName <name>` | 生成项目名 |
| `--packageName <package>` | 生成项目 Java 包名 |
| `--outputDir <path>` | 输出目录；默认 `target/generated/<projectName>` |
| `--zeroVersion <version>` | 生成项目依赖的 zeroServer 版本 |
| `--force` | 对已有目录执行受 ownership 约束的更新；不绕过手工修改冲突或旧 manifest 迁移要求 |
| `--skipInstall` | 跳过根仓库 `mvn -q -DskipTests install` |
| `--skipTests` | 跳过生成项目 Maven `clean test` |
| `--skipRun` | 跳过生成项目 Maven `exec:java` |

## 5. 推荐使用顺序

```text
ZeroLocalDoctor
  -> ZeroArchitectureGuard
  -> mvn -B -ntp test
  -> RunLocalPrototype --fromKeywords "<需求关键词>"
  -> 阅读 <outputDir>/BUSINESS_GUIDE.md
  -> 在抽取正式模块或修改公共契约前提交 GitHub Design Proposal
```

如果 `RunLocalPrototype` 失败，先按输出中的子命令单独排查：

- `mvn -q -DskipTests install`
- `java scripts/NewLocalGame.java ...`
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
