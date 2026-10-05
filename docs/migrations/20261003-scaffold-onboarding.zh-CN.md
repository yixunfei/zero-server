# 脚手架首次运行路径迁移说明（2026-10-03）

本说明记录 0.x 开发预览中脚手架入口和默认值的调整。目标是让首次使用 Java 的开发者从仓库检查到第一次业务修改保持一条可理解的路径。

## 变更

- `NewLocalGame`、`RunLocalPrototype`、`RunLocalScaffold`、`VerifyLocalScaffolds` 和 `ZeroLocalDoctor` 默认优先使用仓库内 `mvnw` / `mvnw.cmd`。
- 所有脚手架默认 zeroServer 版本统一为 `0.1.2-SNAPSHOT`。显式传入 `--zeroVersion` 的命令不受影响。
- `ZERO_MAVEN_CMD` 可以覆盖 Wrapper，适用于企业镜像或自定义 Maven 启动脚本。
- `NewLocalGame --help` 直接输出常用参数、示例和完整文档入口，不构建 codegen。生成前检查 Java 21；JDK 不满足时提示同时切换 `JAVA_HOME` 和 PATH。
- `RunLocalPrototype` 的参数和环境错误分别使用 `RUNNER-INVALID-ARGUMENT` 与 `RUNNER-ENVIRONMENT`，并返回非零退出码。
- local 模板 README 和 `BUSINESS_GUIDE.md` 将第一次使用路径收敛为运行、修改移动坐标、再次测试；明确 demo 内嵌 BO 与独立异步 BO 的实际位置。`COMPONENTS.md`、`NEXT_STEPS.md` 和 `zero-scaffold.json` 保留为深入阅读材料。
- 生成成功的后续命令优先展示当前仓库 Wrapper；修复 Git Bash 在向 Java 工具传递 Maven 路径时可能选择 POSIX 脚本的问题。

## 影响

已有脚手架项目不会被自动改写。重新生成或升级时，如果未显式传入版本，将使用 `0.1.2-SNAPSHOT`；需要继续使用旧坐标的项目应显式传入原版本并确认本地仓库中存在该制品。

Windows 用户无需把 `mvn.cmd` 放在 PATH 中即可使用仓库 Wrapper。需要替换 Maven 的团队应在当前终端设置 `ZERO_MAVEN_CMD`。

## 验证

建议依次执行：

```text
java scripts/ZeroLocalDoctor.java --help
java scripts/RunLocalPrototype.java --help
java scripts/NewLocalGame.java --help
java scripts/ZeroUnifiedEntryVerifier.java
```

本轮在 Windows、JDK 21.0.4、Wrapper Maven 3.9.8 下完成：

- `./mvnw.cmd -B -ntp -pl zero-codegen -am test`：209 项测试通过，其中 codegen 88 项。
- `./mvnw.cmd -B -ntp -DskipTests install`：62 个 reactor 项目构建安装成功；此命令跳过测试。
- `./scripts/zero.ps1 init --projectName onboarding-first-change --packageName group.example.onboarding --outputDir target/verification/scaffold-onboarding/first-change`：安装、生成、结构检查、测试和 smoke 全部通过。
- `java scripts/VerifyLocalScaffolds.java --outputDir target/verification/scaffold-onboarding/matrix`：七类业务模板生成、文档检查、测试和运行通过。
- 按业务指南把位置从 `(3,5)` 改为 `(4,6)`：先确认旧断言失败，再更新断言；测试通过且运行摘要包含 `position=4,6`。
- PowerShell 与 Git Bash 的 Doctor、生成器帮助，以及 Git Bash 的结构检查模式 init 通过；低版本 JDK、错误参数和无效 Maven 路径得到预期退出码和修复提示。

所有 JDK 切换仅限验证进程，没有修改系统环境变量。完整生成和 smoke 需要 Java 21；Maven 可由 Wrapper 提供。Linux/macOS 原生运行尚未实测。

## 回滚

- 继续使用旧版本：所有生成命令显式增加 `--zeroVersion <旧版本>`。
- 暂时绕过 Wrapper：设置 `ZERO_MAVEN_CMD` 为团队认可的 Maven 命令。
- 恢复旧文档不影响已生成项目；不建议回退入口脚本，因为旧入口在 Windows 上可能绕过仓库 Wrapper。

## 未覆盖风险

当前验证仍不替代真实外部 Adapter、容量、长稳和生产部署验收。JDK 低于 21 时只能验证帮助和参数解析，不能完成 codegen 或生成项目编译。
