# 项目验收与异步 Adapter 准入（2026-10-11）

## 变更与影响

`RuntimeAdapterIo.observe()` 现在先分类远程失败并释放 in-flight 配额，再完成返回的 `CompletionStage`。此前在源 stage 的完成线程上同步执行的下游回调，如果立即调用同一 Adapter，会看到尚未释放的旧配额，并在容量满时得到 `OPERATION_REJECTED`。新顺序保证回调观察到该操作已退出容量预算。远程失败仍先更新降级状态，未知写结果仍不重试；分类逻辑本身抛出异常时，结果会异常完成，不再悬挂。

公开 API、配置键、协议、存储格式及数据迁移均不变。以前依赖完成回调执行期间仍占用旧配额的调用方应移除该假设。独立 Redis 消费者的 direct IO 负例改为显式传入 `ZeroRuntimeExecutors.direct()`；默认 `ProductionAssembly.builder(config)` 按需创建受管 remote IO 的既有行为不变。

## 验证与回滚

修复前新增的回调回归在 `zero-runtime-production` 失败，错误码为 `OPERATION_REJECTED`。修复后回调及分类器异常回归、目标模块质量门禁、独立消费者示例均通过；整仓单测、质量与本地集成门禁通过。完整 Stage 0 的前 16 项通过，第 17 项遇 Windows 子进程启动错误后以 Maven 绝对路径单独重跑通过，第 18 项也单独通过。详细命令、结果及环境失败记录在本地归档任务 `tasks/archive/20261011-project-review/VERIFY.md`。外部中间件故障、跨平台 CI 和线上部署不由本地检查证明。

可撤销本轮源码和测试提交回滚；不需恢复数据。回滚会重新引入完成回调误拒绝问题。
