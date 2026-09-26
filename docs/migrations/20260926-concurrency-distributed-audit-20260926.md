# 线程、缓存与分布式一致性专项迁移说明

日期：2026-09-26
适用版本：`0.1.0-SNAPSHOT`
分支：`codex/quality-baseline-20260926`

## 变更

本轮按“全仓审查并修复”范围检查线程调度、异步完成、缓存回填、持久化、Redis、RPC、Kafka/Nacos、运行时资源以及 World/Player/Room/Frame/NPC/Network 状态边界。保持现有架构和公共接口，修复有控制流证据且可用确定性测试复现的问题。

- Actor、EventBus、Runtime、Frame 和 Netty 生命周期在取消、关闭、拒绝和异步完成交错时保持所属 lane/资源的代际与所有权；旧句柄或迟到完成不能删除新登记、推进已关闭状态或遗留 pending。
- `LayeredCacheService`、`CacheLoadCoordinator` 和 `CacheKeyOperations` 使用读取代际、版本条件和取消传播，阻止迟到 L2/loader 回填复活失效值或覆盖新突变。
- Redis cache/data envelope、持久化报告和外部测试认证路径保持失败显式；Redis 条件写/删不能被旧请求覆盖，失败本地 journal 不再伪装成已记录成功。
- RPC client、remote Actor gateway、Kafka pending request 和服务发现 resolver 在调用方取消、超时、路由刷新和 transport 完成交错时释放本地资源并保留至少一次投递边界。
- World、Player、Room、NPC、Frame 和 network 状态更新采用版本/生成号或所属执行域 fencing；simulation 外部副作用仍由业务自行补偿。

## 影响与迁移

没有新增协议字段、Redis 数据格式或公共 API，也没有要求应用修改配置。升级后应重新运行本地测试；缓存 key 仍按现行 `k1:<type>:<payload>` 规范生成，迟到旧缓存会自然失效并在下一次读取时重建。调用方若依赖“取消必然撤销远端执行”，需要改为幂等键、版本检查或补偿流程，因为本轮只增强本地 transport/pending 清理。

外部 Kafka/Nacos 测试必须同时提供真实服务、认证配置并启用 `-Dzero.external.tests=true`；缺少任一条件时测试按设计拒绝运行。Redis 外部验证使用本机专用实例 `127.0.0.1:6387`，不代表集群故障转移已验证。

## 验证

- 定向缓存、RPC、Kafka/Nacos 配置边界、Redis、Actor、EventBus、Runtime、World/Frame/Network 和业务状态回归均通过。
- `git diff --check`、`ZeroArchitectureGuard`、`ZeroFrameworkBoundaryGuard`、`VerifyPublicApiCompatibility --check` 和 `VerifyApiCompatibilityConsumer` 均通过。
- `mvnw.cmd -B -ntp "-Pquality,benchmarks,integration-tests" install` 在 Windows 11/JDK 21.0.4/Maven Wrapper 3.9.8 下通过；最新报告统计 826 个 Surefire/Failsafe 测试，失败、错误、跳过均为 0。质量插件和 benchmark smoke 均执行，benchmark 结果不是性能承诺。
- Kafka/Nacos 真实外部链路未执行：本机没有启用外部测试 profile，也没有提供对应服务和认证材料；直接运行被 profile 守卫拒绝，不能作为链路通过证据。

## 回滚

本轮没有数据迁移。回滚代码后只需恢复同一版本源码并重新运行上述门禁；若回滚到包含旧缓存实现的版本，必须清理或等待旧 L1/L2 条目自然过期，避免把旧值误当作当前事实。

## 未覆盖风险

- 没有生产负载、网络分区、进程强杀或长时故障注入证据；Redis 只验证单实例。
- World 外部 Actor handler 占用目标 lane 导致循环等待仍无法完全自动识别。
- simulation 外部副作用异常时不会自动回滚；业务仍需幂等和补偿。
- `RpcRemoteActorGateway` 二参构造仍表示 local unsigned 模式，不能当作远程认证配置。
- 本轮是基于控制流证据和回归用例的专项审查，不能声称消除了所有可能 bug 或证明生产容量/SLA。
