# Runtime Adapter 恢复与组件入口（2026-10-10）

本次 `0.x` 变更为 Production Redis（数据与缓存共享）、MongoDB、PostgreSQL、Kafka RPC、Nacos 增加运行时健康监测。已知远程连接/超时故障使 Adapter 从 READY 进入 DEGRADED，周期探测经 RECOVERING 返回 READY；停用后不能由迟到探测恢复。准入耗尽或降级时返回 `OPERATION_REJECTED`，可能已发送的写入发生远程故障时返回 `WRITE_OUTCOME_UNKNOWN`，读取返回 `REMOTE_IO_FAILED`。未知结果绝不自动重发；业务需对账或使用自身幂等键。

升级自定义组件时，单值、无需额外资源的 Java 模块可改用 `RuntimeModule.value(id, key, value)`；catalog 的安装表达式也可调用它。复杂组件继续使用显式 Provider。Redis resource 和 Nacos discovery 新增 `zero.executors` 必需能力，生产组合根须安装受管执行器，规划会在缺失时拒绝。外部 Adapter 的新监测可能让过去直接透出的驱动连接异常变成安全的框架错误码；业务应按错误码区分拒绝、读取失败和未知写结果，不应按驱动异常消息重试。

运行时监测不提供对同步 SDK 的硬取消。Nacos `ServiceDiscovery` 仍是同步 API；不要从 Actor/Netty 线程直接调用。Kafka pending 与服务端至少一次投递语义不变。Nacos SDK 状态检查不保证每次探测都执行远端往返。Redis 真实断连/恢复及其他外部集群演练的验证状态以任务 `VERIFY.md` 为准。

2026-10-11 真实 Redis 7.4.2 验证补充：通过 TCP 代理断连与无响应故障验证 Production 数据/缓存共享降级、拒绝、超时、恢复及未知写结果不自动重发。该演练发现并修复 `ScopedEnvelopeStore` 把非数据类框架错误覆盖为 `READ_FAILED`/`WRITE_FAILED` 的问题；现在保留原框架 ErrorCode，不增加 data → runtime 依赖。调用方应处理实际 `OPERATION_REJECTED`、`REMOTE_IO_FAILED` 与 `WRITE_OUTCOME_UNKNOWN`，而非统一重试一般数据错误。此验证不覆盖 Redis 进程重启、复制集切换和其他四类服务故障。

## 2026-10-11 上手复查修复

- ProductionAssembly 两个默认 builder 延迟到 build 创建受管 remote IO；依赖 direct 的自定义集成应显式选择 base。真实 Adapter 仍拒绝 direct。ProductionContext 移除未使用的 executors 字段；Provider 从创建上下文取得 RuntimeBasics.EXECUTORS。
- ComponentContribution 新增运行期健康快照源；descriptor 必须声明相应 READINESS/LIVENESS。GameRuntime.healthSnapshot 保留 STARTUP 并读取当前就绪；非 RUNNING 不报告就绪，源异常转换为 health-snapshot-failed。
- ZeroProductionAdapterStatus 增加 runtime/settings 两个 Optional 字段，直接构造记录的应用须补充。运行期降级原因取 runtime.healthCode，启动失败字段仍表示启动/关闭阶段失败。
- RuntimeModule.component(id, key).require(...).create(...) 创建的 AutoCloseable 由运行时拥有；借用对象继续使用 value。工厂内中间资源仍需及时登记。catalog 新增 bindings 简写；复杂目录继续使用完整显式声明。
- 五类 Adapter 使用 zero.adapter.<redis|mongo|postgresql|kafka|nacos>.operation-timeout-millis、max-in-flight、probe-interval-millis。Kafka 旧 zero.rpc.kafka.io-timeout-millis 改为 zero.adapter.kafka.operation-timeout-millis，不保留旧键别名。Redis/MongoDB/PostgreSQL 默认运行超时 10000 ms，Kafka 3000 ms，Nacos 按 SDK 配置默认值。启动预算不再改变原生运行超时；PostgreSQL 最低 1000 ms，Nacos 原生 request timeout 不得超过运行预算，启动窗口仍须容纳 SDK 启动。
- RuntimeAdapterIo 工厂使用 RuntimeAdapterSettings 替代单独 Duration；固定探测间隔可配置，业务自动重试仍为 0。共享预算和失败语义见[配置指南](../guides/configuration-and-catalog.zh-CN.md)。
- 生成工程增加 Wrapper 并纳入归属清单。已有工程用相同目录参数执行 --plan/--apply 审阅新增文件。Runner 支持 components/catalog，只安装所选模块上游；更新源码后不要使用 --skipInstall。NewLocalGame 仅生成，不安装应用依赖。
- InspectLocalScaffold 现在检查 Wrapper 文件；旧生成目录需先按上述升级流程补齐 Wrapper。runtime 与业务模板使用同一个 RunLocalScaffold；跳过测试但继续运行时先 compile。

验证证据（Windows、JDK 21）：`mvnw.cmd -q -pl zero-server-starter-production,zero-codegen -am -Pquality,integration-tests -Dit.test=RedisRuntimeRecoveryExternalIT -Dfailsafe.failIfNoSpecifiedTests=false -Dzero.external.tests=true -Dzero.redis.uri=redis://127.0.0.1:16389/0 verify` 退出码 0，Redis 真实故障集成测试 1 项通过。最小 runtime、greeting catalog、Redis runtime 生成工程的统一 `RunLocalScaffold` 测试与运行通过；带 config 依赖的 catalog 工厂及 local 模板跳过测试后的编译运行也通过。`ZeroUnifiedEntryVerifier` 的 Windows 静态验证通过。`ZeroArchitectureGuard` 因既有 Jedis 8.0.0→8.0.1、Netty 4.2.17→4.2.18 依赖清单漂移退出 1，模块边界检查通过；未调整该基线。

本地详细日志和过程记录在 `tasks/active/20261010-runtime-adapter-recovery/VERIFY.md`（任务目录按仓库规则不纳入版本控制）。真实故障演练仍仅覆盖 Redis；其他四类服务、Nacos 同步调用线程隔离、集群切换及高并发容量不能据此视为已完成验收。
