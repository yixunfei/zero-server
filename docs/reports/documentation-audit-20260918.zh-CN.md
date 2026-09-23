# 文档现状核对（2026-09-18）

## 范围与基线

基线为 `codex/documentation-cleanup` 上 `2b21637` 加任务开始时已有的未提交源码、模板、脚本和文档修改。此报告核对该工作区，不将 HEAD 或旧报告当作当前实现。环境：Windows、Java 21、Maven 3.9.8。

本轮延续现有 guides/reference/operations/reports 分类，更新当前说明、导航和验证边界，没有新增运行时功能或改变公共 API。既有行为修复见[9 月 17 日迁移说明](../migrations/20260917-bug-report-verification.md)，文档调整见[本轮迁移说明](../migrations/20260918-documentation-audit.md)。

## 纠正的内容

| 原说明或问题 | 当前处理与依据 |
| --- | --- |
| 模板目录说没有 net 选项 | `ScaffoldComponents` 和 `ProjectScaffoldGenerator` 已支持选择及 Server 文件生成；补充当前编译限制，避免宣称开箱即用 |
| 路线图把 TCP 入口、Kafka 三模块全部列为待开发 | 区分已有实现、生成装配缺陷和外部验收；保留治理与生产缺口 |
| 事件能力笼统写“重试” | 对照 `InMemoryEventBus`，明确失败后继续、最终汇总异常和默认不自动重试 |
| 迁移与报告索引遗漏后续交付 | 收录现有迁移记录及缺陷核验报告；示例索引增加 Kafka 三模块 |
| 公开报告链接到被忽略的 tasks | 提供公开复现命令，说明本机原始日志和备份不随仓库分发 |
| Changelog 重复、日期条目游离于版本之外 | 移入 Unreleased，删除重复的事务锁条目 |
| Runner 的 force 描述缺少限制 | 明确 ownership 冲突和旧 manifest 迁移要求 |
| 网络文档仍称统一线程管理未落地 | 区分受管业务 executor 与 Netty 自有 IO 线程组 |
| 模块图未列出新入口 | 补充脚手架 CLI/升级服务与 TCP 生命周期门面 |

历史报告与迁移中的数字保留原日期。活动任务中仍有安全、恢复、GM、跨平台或外部服务未完成项，不批量归档；早期缺陷判断注明后续复核入口。

## 当前已复现的限制

新生成 `local + net` 工程的 `RuntimeAssembly` 调用 `NetworkRuntime.module()`，而当前 `zero-runtime-net` 只提供带 `ProductionNetworkPolicy`、`NetworkRateLimiter`（及可选 `SecurityChain`）参数的重载，导致编译失败。

独立生成消费者矩阵也在 `runtime + net` 的 `component-net` 处复现同一问题；此前 21 个消费者通过，后续组合因首错停止未执行。本轮不能宣称完整生成组合矩阵通过。

复现步骤：

```bash
mvn -B -ntp -DskipTests install
java scripts/NewLocalGame.java --template local --components net --projectName tcp-demo --packageName group.example.tcpdemo --outputDir target/tcp-demo
mvn -q -f target/tcp-demo/pom.xml clean test
```

输出目录须为空。生成成功，最后一步退出 1，报 `NetworkRuntime.module()` 无匹配方法。不能使用旧 TCP smoke 日志覆盖本次失败。应先修复生成器的显式网络策略接线并重新验收，再将此模板提升为可运行入口。本轮文档整理不引入网络策略默认值，也不改变安全 API。

可用替代入口为 `examples/rpg-tcp-generated`，本轮实际通过测试和真实 loopback 请求/响应。

## 本轮验证

| 命令 / 检查 | 结果与边界 |
| --- | --- |
| 可提交 Markdown 链接及导航检查 | 115 份文档、442 个本地链接与锚点，0 失效；从根 README 可到达 docs/examples/模块文档，0 孤立页面 |
| `git diff --cached --check` | 通过；本机任务、日志及工具记忆未纳入提交 |
| `mvn -B -ntp -T 1C -Pquality install` | BUILD SUCCESS；173 个测试类、673 个测试，失败/错误/跳过均为 0；执行 Checkstyle、PMD、SpotBugs 和 JaCoCo 报告 |
| `java scripts/ZeroLocalDoctor.java` | 27/27 |
| `java scripts/ZeroArchitectureGuard.java` | 56 模块、20 规则，0 violations/warnings |
| `java scripts/ZeroFrameworkBoundaryGuard.java` | 通过，未提升生产能力声明 |
| `java scripts/VerifyLocalScaffolds.java` | 默认七类业务模板全部完成生成、测试、运行；不含额外 net 组件 |
| `mvn -B -ntp -f examples/rpg-tcp-generated/pom.xml clean test exec:java` | 1 个测试通过；`rpg-tcp=ok`，业务线程处理与 session 关闭 marker 均出现 |
| `mvn -B -ntp -f examples/modular-composition/pom.xml clean test` | 全部子模块通过；不证明真实中间件连通 |
| `mvn -B -ntp -f examples/modular-composition/center-logic-kafka/pom.xml clean test` | 三模块构建通过；没有运行 broker 或双 JVM |
| 新生成 `local + net` 工程 `clean test` | 失败；如上记录装配 API 不匹配 |
| `java scripts/VerifyGeneratedCompositions.java` | 21 个消费者通过，随后 `component-net` 因相同装配 API 不匹配失败；后续组合未执行 |

本机日志保存为 `target/documentation-audit-*.log`，仅用于本轮维护，不作为公开读者的前置资料。根 reactor 测试统计不含独立 examples 或生成工程；JaCoCo 报告生成不等同于达到额外覆盖率门槛。

未验证：远端 URL、当前代码在 Linux/macOS 的行为、真实 Kafka/Nacos/数据库联调、生产安全闭环、容量与长稳。`productionReady=false` 保持不变。
