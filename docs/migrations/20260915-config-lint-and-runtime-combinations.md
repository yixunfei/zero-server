# 20260915 配置 lint 与 runtime 组合验收切片

## 变更

将验收矩阵中的三个 skipped 项替换为真实本地证据：

- `matrix.config-lint`：新增 `scripts/ZeroConfigLint.java`，静态检查 scaffold metadata、组件/外部服务标志、schema 版本、端口副作用和示例 properties 重复/敏感值；不会加载 runtime 或创建任何资源。
- `matrix.empty-runtime`：严格零 module、零 requirement、零 component/edge/capability 的 `RuntimeComposition` diagnose/build/start/close 测试。
- `matrix.event-actor`：EventBus、DeadLetterSink、ActorScheduler 和 Executors 的 required closure 及 event→actor dispatch 测试，同时保留无 starter/Netty/data/Redis 依赖断言。

## 退出码与证据

`ZeroConfigLint` 约定：0 通过、1 配置不一致、2 参数错误、3 输入材料缺失；敏感值只输出 `<redacted>`。验收工具为每个切片写入独立日志：

- `target/acceptance-evidence/logs/config-lint.log`
- `target/acceptance-evidence/logs/empty-runtime.log`
- `target/acceptance-evidence/logs/event-actor.log`

## 验证

```text
java scripts/ZeroAcceptanceEvidence.java --level quick --no-stage0 --maven mvnw.cmd
```

本地 Java 21 结果：`zero-acceptance-evidence=ok`，三个新增 record 均为 `passed`，`failed=0`、`missing=0`。

## 回滚与边界

删除 lint 脚本、组合 focused assertions 和 evidence record 即可回滚，不影响 runtime 公共 API 或协议。该切片只证明本地组合隔离和静态配置元数据，不证明外部 adapter、跨平台 runner、生产容量、安全、恢复或长稳；`productionReady=false`、`goalAchieved=false` 继续有效。
