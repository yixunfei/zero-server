# 质量基线与阻塞修复（2026-09-26）

## 范围

基于主线 `cde220b` 及已有工作区审计修改，先将 35 个文件的当前改动保存为本地备份提交 `0c0e2aa`，再修复本轮确认的阻塞。验证环境为 Windows 11、JDK 21.0.4、Maven Wrapper 3.9.8。系统默认 Java 17 不满足要求，本轮仅为验证进程选择已安装的 JDK 21。

本轮关注全仓构建、现有行为测试、静态质量与入口可运行性；不声称逐行审计全部源码。9 月 24 日报告中的候选问题也不因默认测试通过就自动获得专项验证。

## 主要发现与修复

| 发现 | 证据与处理 |
| --- | --- |
| Production Starter 装配回归 | 首轮 787 项测试中 2 failures、8 errors，全部位于 Production Starter。全局禁用 LOCAL 同时禁掉基础图唯一的持久化管理器；启用外部 Adapter 也无法解除冲突。经确认恢复既有按需装配契约，保留显式外部组件的配置、健康、预算及失败终止约束。 |
| 文档状态滞后 | README 和能力矩阵仍说 net 生成工程编译失败，但 9 月 23 日已修复；历史审计索引也把已经发生修复的候选统一写成未修复缺陷。本轮同步当前状态并保留历史证据日期。 |
| 静态门禁不能替代行为验证 | 初始 `-Pquality -DskipTests verify` 成功，但同一源码有上述 10 项测试失败。修复后必须执行不跳过测试的完整门禁。 |

运行时代码仅调整 `ProductionAssembly` 的默认 profile 约束，未修改协议、权限、存储格式或线程模型。新增 3 项测试验证外部组件缺少健康声明、创建失败和健康失败均不会回退本地，并检查资源回收；既有默认 Starter 测试扩展到实际启动。

## 验证记录

| 验证 | 结果 |
| --- | --- |
| `java scripts/ZeroLocalDoctor.java` | 27/27 通过。 |
| `java scripts/ZeroArchitectureGuard.java` | 56 模块、20 规则、0 违规、0 警告。 |
| 首轮 `mvnw.cmd -B -ntp --fail-at-end test` | 787 项，2 failures、8 errors、0 skipped；57 reactor 项目中 56 成功。 |
| 首轮 `mvnw.cmd -B -ntp --fail-at-end -Pquality -DskipTests verify` | 57 reactor 项目通过；仅静态质量基线，旧 JaCoCo 报告不计入本轮覆盖证据。 |
| 定向 `mvnw.cmd -B -ntp -pl zero-runtime-production,zero-server-starter-production -am test` | 48 reactor 项目通过；原先 10 项失败恢复，新增 3 项无回退测试通过。 |
| `java scripts/VerifyPublicApiCompatibility.java --check` | 5 个受保护模块通过，只允许增量符号；本轮不改变 API。 |
| `java scripts/VerifyApiCompatibilityConsumer.java` | 独立消费者编译通过。 |
| `java scripts/ZeroFrameworkBoundaryGuard.java` | 5 个保护根目录、7 个玩法标记检查通过，未声明生产就绪。 |
| `mvnw.cmd -B -ntp "-Pquality,benchmarks,integration-tests" install` | 58 reactor 项目全部成功；Surefire 791 项、Failsafe 1 项，失败/错误/跳过均为 0；4 分 15 秒。包括 Checkstyle、PMD、SpotBugs、JaCoCo 报告和 benchmark smoke，不是性能测量。 |
| 新生成 `local + net` 工程 | `clean test` 成功，3 项测试通过；随后独立执行 Server `--port=0 --once`，真实 loopback TCP 回显成功，进程退出。 |
| `java scripts/VerifyGeneratedCompositions.java` | 26/26 组合生成、编译、测试、运行或外部诊断及依赖闭包检查通过；外部组合只诊断，未连接真实中间件。 |

本机原始日志保存在 `target/quality-baseline-20260926/`，该目录为生成物，不提交到仓库。

本地 TCP 验收可复现命令（生成到新的输出目录）：

```powershell
java scripts/NewLocalGame.java --template local --components net --projectName quality-net --packageName group.zn.qualitynet --outputDir target/quality-baseline-20260926/net-project
.\mvnw.cmd -B -ntp -f target/quality-baseline-20260926/net-project/pom.xml clean test
.\mvnw.cmd -B -ntp -f target/quality-baseline-20260926/net-project/pom.xml exec:java "-Dexec.mainClass=group.zn.qualitynet.QualityNetApplicationServer" "-Dexec.args=--port=0 --once"
```

成功标记为 `tcp-server=ready` 和 `local-game-tcp=ok|protocol=90101`。本轮首次把 `clean test exec:java` 与 `-Dexec.args=--port=0 --once` 合并时，协议生成器收到 `--port=0` 并拒绝；分开执行后通过。这是验收命令参数作用域问题，不是生成工程编译缺陷。

## 剩余边界与建议

- Checkstyle 当前覆盖文件/方法长度等基础约束；PMD 只配置 `EmptyCatchBlock`，SpotBugs 使用 High 阈值；JaCoCo 生成报告，没有配置覆盖率达标检查。全绿说明通过当前规则，不等同于完整设计或安全审计。
- production 档位不会强制应用使用持久化存储。应用必须明确选择外部 provider、Repository 来源和自己的关键能力限制，不能把档位名当成生产可用声明。
- 优先继续对既有审计候选逐条构造稳定复现，特别是数据一致性、回调失败与并发状态边界；不要仅按历史报告严重度批量修改核心语义。
- 外部 Redis/MongoDB/Kafka/Nacos/PostgreSQL 故障、跨平台、容量和长稳没有在本轮执行；维持 `productionReady=false`。
- 本轮没有重新运行完整 Stage 0 编排及其全部独立示例、七类业务模板，不能把本次质量与生成组合验证标记为 Stage 0 full 通过。

迁移、影响和回滚见[迁移说明](../migrations/20260926-quality-baseline.md)。
