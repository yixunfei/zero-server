# 20260914 local-game-scaffold 异步边界迁移说明

> 本页未完成项属于当时的验证范围。后续 TCP/Kafka 实现与当前限制以[能力矩阵](../capability-matrix.zh-CN.md)和[路线图](../optimization-roadmap.zh-CN.md)为准。

## 1. 版本与责任信息

- 适用版本：`0.1.0-SNAPSHOT`（0.x 开发预览）
- 变更类型：生成模板职责拆分与业务异步语义收紧
- 责任范围：`templates/local-game-scaffold`、`zero-codegen` 生成映射
- 机器标记：`zero-migration-guide-template=template|sections=9|breakingChanges=true|rollback=true|migrationResult=false`

## 2. 变更摘要

local 模板现在将 composition root、`LocalGameBO`、`LocalGameFlow`、`LocalGameFixture` 和 `LocalGameObservation` 生成为独立文件。Player/scene 业务端口仍由框架注入并返回 `CompletionStage`；协议业务适配器使用完成回调观察结果，不在 handler 或 Actor 路径同步等待。

## 3. 破坏性变更

旧模板把业务 BO、服务装配、流程编排、日志和指标聚合集中在 `Application.java`。新模板要求业务修改优先进入独立的 `LocalGameBO`/service port 文件。既有 `runDemo` 摘要和协议 ID 保持兼容；最外层 smoke 编排保留一次等待，但这不扩展为网络服务或生产就绪承诺。

## 4. 迁移前准备

1. 运行当前生成工程的 `mvn -q clean test` 并保存日志。
2. 备份手写的 `Application.java` 和 `zero-scaffold.json`。
3. 检查业务代码没有在 IO/Actor handler 中调用 `join()` 或 `get()`。
4. 阅读 `docs/threading-model.zh-CN.md` 和生成工程的 `BUSINESS_GUIDE.md`。

## 5. 迁移步骤

1. 使用脚手架的 `--plan`/`--diff` 检查 ownership 变更。
2. 将业务端口和状态修改移动到 `LocalGameBO` 及注入的 player/scene service。
3. 将流程顺序和 demo 结果聚合移动到 `LocalGameFlow` 或 composition root。
4. 使用 `LocalGameFixture` 管理服务订阅，禁止手动创建或关闭框架线程池。
5. 使用 `LocalGameObservation` 记录完成和失败，不通过阻塞等待推断结果。
6. 运行 `mvn -q clean test`，确认生成的 `LocalGameAsyncTest` 通过。

## 6. 验证

本次验证命令：

```text
mvnw.cmd -pl zero-codegen -Dtest=ProjectScaffoldGeneratorTest,ScaffoldManifestContractTest -Dsurefire.failIfNoSpecifiedTests=false test
java scripts/NewLocalGame.java --template local --projectName async-local --packageName group.example.async --outputDir target/async-local-scaffold --force
mvnw.cmd -f target/async-local-scaffold/pom.xml clean test
mvnw.cmd -f target/async-local-scaffold/pom.xml exec:java
```

结果：生成器和 manifest 契约测试通过；生成工程 `clean test` 通过；Java 21 下 smoke 输出 `local-game=ok|mode=local|name=async-local|uid=1001|position=3,5|logs=4|metrics=2|maxProtocolId=90105`。API compatibility baseline 未发生变化。

## 7. 回滚

在升级事务产生冲突或行为不符时，使用 `--abort` 放弃未提交事务，或使用 `--rollback` 恢复上一次已提交事务。若尚未迁移，保留旧 `Application.java` 并停止模板升级；不得使用 `--force` 绕过 generated 文件冲突。

## 8. 发布后观察

关注生成工程的异步 stage 是否异常完成、超时、取消和重复请求是否符合业务定义；关注 Actor lane 顺序、日志 traceId 和观测计数。当前 dispatcher 仍是同步布尔触发 API，尚未证明真实网络异步回写、长驻 listener 或生产级背压。

## 9. 完成确认

- [x] 独立模板文件已加入 ownership manifest。
- [x] 业务适配器不使用无控 `join()`/`get()`。
- [x] 生成工程 smoke 和异步 fixture 测试已验证。
- [x] `productionReady` 保持 `false`。
- [ ] 真实 TCP 长驻、Kafka 双进程、生产安全/持久化/容量和跨平台故障恢复仍未证明。

<!-- zero-migration-verification-and-rollback=required -->
