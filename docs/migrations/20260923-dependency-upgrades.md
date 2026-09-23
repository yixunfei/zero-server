# 2026-09-23 依赖升级与候选合并

本次将经过验证的 Dependabot 依赖升级合入 `main`。项目仍处于 `0.x` 开发预览阶段，升级后的完整组合为：

- MongoDB Java Driver `5.9.2`
- Netty `4.2.17.Final`
- Nacos Client `3.2.3`
- Jedis `8.0.0`
- GitHub Actions `actions/setup-java@v6`
- GitHub Actions `actions/upload-artifact@v7`

SpotBugs Maven Plugin `4.10.3.0` 未合入。使用该版本执行完整 `quality,benchmarks,integration-tests` 门禁时，`zero-protocol` 在 `NativeMemoryZeroBuffer` 和 `ZeroUnsafe` 报告 11 个 `UNS_UNSAFE_CALL`；本次保留 `4.9.3.0`，没有通过 suppression 绕过质量门禁。

## 影响与回滚

MongoDB、Netty、Nacos 和 Jedis 的版本声明发生变化，运行时依赖解析会使用上述版本。Actions 版本只影响 CI 执行环境。若升级后出现回归，将相应版本属性或 workflow action 引用恢复到上一版本，并重新运行完整门禁。

合并前增加了 `ZeroManagedSchedulerFactoryTest` 的有界线程退出等待，修复线程池关闭尾部的偶发竞态；生产调度器实现未改变。

## 验证

本机通过：

```text
mvn -B -ntp -Pquality,benchmarks,integration-tests verify
```

58 个 reactor 项目全部成功，包含 Checkstyle、PMD、SpotBugs、Surefire、Failsafe 和 benchmark smoke。远端 CI 仍需对最终候选提交运行并核对。Redis、MongoDB、Nacos 的真实外部服务测试因本机 Docker Desktop backend 启动失败而未执行，不能将本次结果视为外部组件验收。

历史性能报告基于旧依赖组合，不能作为上述升级版本的性能实测证据。

## 生成网络组件与验收入口修复

`net` 生成装配使用现有带参数 API，默认拒绝握手并将 limiter 留给框架内置有界实现；所选 `zero.net.lifecycle.enabled=true` 与清单 provider 对齐。没有新增无参兼容 API，也没有开放匿名生产接入。`runtime + net` 移除多余聚合 Starter；`local + net` 的 TCP 示例继续保留所需 Starter。已有生成工程请先备份，按 ownership 升级流程检查冲突后重新生成，应用安全策略必须显式提供。

验收入口先安装当前 reactor 依赖，再在拥有目标测试的模块执行指定测试，保留“找不到测试即失败”的约束；修复 CLI classpath 分隔符、Windows Path 继承和空工作区误报 dirty。普通 CI 使用 `--local-only --no-stage0`：Kafka 与外部持久化证据标记 skipped，Stage 0 由同一 workflow 独立必跑 job 执行。默认不加 `--local-only` 的完整证据采集仍要求外部证据，不将 blocked 改记 passed。

复验命令：

```text
mvn -B -ntp -DskipTests install
java scripts/VerifyGeneratedCompositions.java
java scripts/ZeroAcceptanceEvidence.java --level full --local-only --no-stage0
```

Stage 0 复验另发现快速 bind 已完成时 Netty `sync()` 不检查预先中断，导致启动是否取消取决于时序。共享 TCP/HTTP/UDP 资源入口现显式检查中断，调用层保留 `START_FAILED` 与中断状态，并关闭本次 listener、不关闭借用 IO 组；增加已完成 future 的确定性回归用例。业务不需要迁移。
