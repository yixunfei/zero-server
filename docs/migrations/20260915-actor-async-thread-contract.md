# 20260915 WP-02 Actor 异步线程契约迁移说明

## 变更

补充本地 direct 与 executor-backed Actor 的可验证边界：

- `LocalActorScheduler` 继续作为调用线程推进的确定性实现，不创建或关闭线程池。
- `ExecutorActorScheduler` 只使用注入的外部执行器；同一 lane 串行，异步 stage 完成后才继续 drain。
- 未完成异步 stage 不得阻塞 Actor 工作线程；异常、取消、超时和执行器拒绝都必须结束 dispatch stage。
- `ZeroRuntimeExecutors.localPrototype` 的 logic、actor、remote IO、background 执行域由 starter 统一拥有和关闭；业务代码不得自行创建 executor。

## 影响

本次只增加 focused tests 和文档，不改变 `ActorScheduler`、`PlayerService`、`SceneService` 的公共方法签名，也不改变 wire 协议或生产 readiness 状态。测试使用受控 `CompletableFuture`、CountDownLatch 和 Java 21 命名执行器模拟远程 IO，不把阻塞 provider 引入 Actor lane。

## 验证

```text
./mvnw.cmd -B -ntp -pl zero-actor,zero-runtime-bootstrap -am \
  -Dtest=ExecutorActorSchedulerTest,LocalActorSchedulerTest,ZeroRuntimeExecutorsFocusedTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
java scripts/ZeroAcceptanceEvidence.java --level quick --no-stage0 --maven mvnw.cmd
```

验收证据记录为 `matrix.actor-async-thread-contract`，原始日志位于 `target/acceptance-evidence/logs/actor-async-thread-contract.log`。

## 回滚

删除新增 focused test、证据记录和本迁移说明即可回退本次验证增强；无需回退 Actor 运行时 API。若未来需要改变 handler 返回类型或调度器阻塞语义，必须另建高风险迁移并取得确认。

## 未覆盖风险

当前证据仍不覆盖 Linux/macOS 文件系统 runner、生产容量/长稳、第三方 provider 的真实阻塞、跨进程 Actor 恢复、TLS/认证、持久化恢复和生产级背压。因此 `productionReady=false`，整体 `goalAchieved=false`。
