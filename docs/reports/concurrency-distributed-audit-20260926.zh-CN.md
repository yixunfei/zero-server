# 线程、缓存与分布式一致性专项审查报告

日期：2026-09-26
仓库：`L:/zero-server`
分支：`codex/quality-baseline-20260926`
环境：Windows 11、JDK 21.0.4、Maven Wrapper 3.9.8

## 范围和方法

本轮覆盖 Actor 调度、异步 stage、取消与关闭、EventBus、定时/运行时资源、分层缓存和 Redis、持久化、RPC/Kafka pending、Nacos 发现、Netty 生命周期，以及 World/Player/Room/Frame/NPC 状态。按调用链和共享状态定位可复现的竞态，先补确定性回归，再做最小修复；未把历史报告中的候选直接视为当前缺陷。

## 确认并修复的问题

| 领域 | 缺陷和修复 | 主要位置 |
| --- | --- | --- |
| Actor/运行时 | 旧注销句柄、executor 拒绝、异步取消/异常、关闭清理交错时可能误删新登记或遗留 lane/资源；加入身份 fencing、预算释放和幂等清理。 | `zero-actor`、`zero-runtime-actor`、`zero-runtime`、`zero-runtime-production` |
| 事件/帧/网络 | 完成回调可能在错误线程推进状态、帧旧快照覆盖新提交、快速 bind/stop 交错造成生命周期遗漏；统一回到所属执行域并按代际/状态校验。 | `zero-event`、`zero-frame-sync`、`zero-net` |
| 缓存 | 超时读的迟到回填可复活失效条目，singleflight action 未传播调用方取消，突变与回填可能逆序覆盖；加入读取代际、条件回填和共享加载取消边界。 | `zero-cache` |
| Redis/持久化 | 条件写/删和失败 journal 记录存在错误成功边界；改为显式失败、条件脚本和完整审计。 | `zero-data-redis`、`zero-data` |
| RPC/发现 | 调用方取消不能结束 transport response，pending 请求清理和路由刷新存在竞态；取消传播、完成一次性清理和 resolver 快照 fencing 已补齐。 | `zero-rpc`、`zero-rpc-kafka`、`zero-discovery-nacos` |
| 业务状态 | World/Player/Room/NPC 的旧生成或旧版本完成可能覆盖新状态，资源回收和重复 spawn 边界不完整；增加版本/生成号和状态门禁。 | `zero-world`、`zero-player`、`zero-room`、`zero-npc` |

## 验证结果

定向回归覆盖新增的缓存回填/突变竞态、Actor stage 边界、运行时 cleanup、RPC cancellation、Kafka pending、安全认证、Nacos resolver、Redis external path、World/Room/NPC/Frame/Network 状态边界。最新完整构建产生 220 份测试报告，共 826 个测试，失败、错误、跳过均为 0。

质量和兼容性门禁结果如下：

- `git diff --check`：通过。
- `ZeroArchitectureGuard`：56 个模块、20 条规则，0 违规、0 警告。
- `ZeroFrameworkBoundaryGuard`：通过。
- `VerifyPublicApiCompatibility --check`：基线/当前均 215，允许增量且无破坏。
- `VerifyApiCompatibilityConsumer`：5 个模块通过。
- `mvnw.cmd -B -ntp "-Pquality,benchmarks,integration-tests" install`：58 个 reactor 模块全部成功，Checkstyle、PMD、SpotBugs、JaCoCo 和 benchmark smoke 均执行。
- Redis 单实例 `127.0.0.1:6387`：`PING` 返回 `PONG`，定向测试通过。

真实 Kafka/Nacos 外部链路没有执行。测试要求 `-Dzero.external.tests=true`、可用 Kafka/Nacos 服务和认证材料；本机未满足这些前置条件，profile 守卫按设计拒绝运行，因此不能把该项写成通过。

## 剩余边界

本报告不证明生产负载、网络分区、进程强杀、长稳、跨平台或 SLA。World 外部 Actor handler 占用目标 lane 的循环等待仍需运行时诊断；simulation 外部副作用失败不会自动回滚；二参 `RpcRemoteActorGateway` 继续表示 local unsigned 模式。专项审查提供的是当前控制流和测试证据，不能声称消除所有潜在 bug。
