# 2026-10-08 复查后续修复迁移

对应 [复查报告](../audits/2026-10-08-audit-recheck.zh-CN.md) R1–R5。本次不改版本、不发布，不改变线格式。

## Frame 关闭（0.x 行为变化）

异步 ActorScheduler 的调用方应将 `runtime.close()` 改成 `runtime.closeAsync()` 并观察完成或失败。先停止外部输入/tick，再等待清理成功，最后由共享资源所有者关闭 scheduler。禁止在当前 match lane 中 `join()` 等待自身清理；用 CompletionStage 组合后续关闭。

```java
CompletionStage<Void> cleanup = runtime.closeAsync();
// 在组合根观察 cleanup 的完成/失败；成功后再关闭共享 scheduler。
```

首次调用即永久关闭准入，排队业务也会拒绝。清理只在原 match lane 执行；并发在途调用合并，失败后再次 `closeAsync()` 重试，成功后幂等。取消返回信号的 CompletableFuture 副本不会取消内部清理。scheduler 已永久停止则无法保证清理，调用方必须保留失败并处理资源生命周期。

同步 `close()` 不阻塞：仅清理已完成时正常返回；未完成时抛出 `ZeroException` 提示观察 `closeAsync()`，失败时抛出 `CompletionException`（原因包含 ErrorCode）。默认 LocalActorScheduler 在 lane 外同步完成的路径可继续使用 try-with-resources。异步 scheduler 应显式管理关闭流程，不能用无条件 try-with-resources 隐藏异步结束。

## 其他调用方

- Player logout 返回值表示精确 account/uid/sessionId 绑定是否被解除。新登录抢在 player 清理前完成时，旧 logout 仍返回 true，保留新会话及已加载数据；过期请求仍 false，调度失败仍异常完成。独立 loadPlayer 契约未收紧。
- Java 生成器明确使用 `java.lang.Class`、注解和异常类名，允许合法用户枚举与 JDK 名称相同。`ZeroPayloadCodec`、`ZeroException`、`ProtocolErrorCode` 是真实模板导入保留符号，`java` 是全限定 JDK 类型的根标识符，冲突输入提前拒绝；须改名再生成。运行生成器更新受管文件，不手改生成物或清单摘要。
- Redis 独立消费者显式使用 `ProductionAssembly.builder("production", config, ZeroRuntimeExecutors.remoteIo("redis-consumer"))`。执行器在外围 try-with-resources 保护，确保 build 尚未接管资源即失败时也释放；成功由 runtime 关闭。默认 direct 与 provider 的非内联检查保留。
- 新依赖门禁/清单流程见[依赖门禁契约](../reference/dependency-guard-contract.zh-CN.md)。
- API 检查使用当前 JDK 的进程内 javap，不依赖 PATH 的子进程启动。旧 `readPresenceBits()` 无参方法删除仍使 0.1.0 基线门禁失败；迁移到 schema 数量参数的要求见 [20261007 迁移](20261007-audit-n-series.zh-CN.md)。未恢复旧方法或重写基线。

## 验证证据

本轮修复前确定性反例、目标回归、架构正负夹具、独立消费者和全仓质量门禁结果见[修复结果](../audits/2026-10-08-audit-followup-results.zh-CN.md)。测试通过不证明不存在其他缺陷；外部 Redis、其他 OS、JMH 与延后产品能力须单独验收。
