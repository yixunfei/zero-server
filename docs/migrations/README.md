# 迁移说明

这些页面记录 `0.x` 开发预览中的行为和 API 调整。它们不表示正式版本兼容承诺；升级前应先运行当前验证命令并检查生成工程差异。

按日期记录的验证结论仅适用于当时环境和代码，不自动覆盖后续修改。没有运行时迁移的工具/证据变更也在此保留，以便追踪验收口径。

| 日期 / 主题 | 适用范围 |
| --- | --- |
| [2026-10-12 模块接入与常见场景应用](20261012-module-recipes.zh-CN.md) | 七个独立应用、16 个模块入口更新；无框架 API/存储/协议迁移 |
| [2026-10-12 新增 examples 审核修复](20261012-examples-review.zh-CN.md) | 每日奖励聚合 schema 2、示例 API/线程约束、外部探针命令及模块接入文档修正 |
| [2026-10-12 模块接入示例](20261012-module-access-examples.zh-CN.md) | 62 个 `zero-*` 模块和根 examples 的 63 张最小接入卡片；无运行时/API 迁移 |
| [2026-10-12 多服务器玩家生命周期示例](20261012-multi-server-player.zh-CN.md) | 注册、登录、角色、断线重连，以及 Kafka/Nacos/MongoDB/Redis Compose 验收；无生产 API 迁移 |
| [2026-10-12 模块示例与使用说明](20261012-module-examples.zh-CN.md) | 全部 Reactor 模块的 examples 入口、依赖选择和可运行场景导航；无运行时/API 迁移 |
| [2026-10-11 项目验收与异步 Adapter 准入](20261011-project-review.zh-CN.md) | 异步完成通知与容量释放顺序、Redis 独立消费者负例及验证边界 |
| [2026-10-11 GitHub Actions 验收修复](20261011-ci-actions-repair.zh-CN.md) | 依赖清单、Wrapper 版本检查、示例和 CLI 验收类路径、macOS 测试临时目录 |
| [2026-10-10 Runtime Adapter 恢复与组件入口](20261010-runtime-adapter-recovery.zh-CN.md) | 五类外部 Adapter 健康、拒绝与未知写结果，以及 Java/catalog 自定义组件入口 |
| [2026-10-09 审计遗漏 F1/F2](20261009-audit-f1-f2.zh-CN.md) | 玩家清理拒绝后的重试、身份代际及 Java 类型/包/导入校验 |
| [2026-10-08 审计 B6](20261008-audit-b6.zh-CN.md) | 生成校验、schema 2 恢复、首建事务、当前构件与真实测试覆盖 |
| [2026-10-08 审计 B5](20261008-audit-b5.zh-CN.md) | 生命周期预算、Event 完成钩子、World 收据、Room 终态、NPC 异步 lane 与 Logic 会话 |
| [2026-10-07 审计 N1–N9 事实复核](20261007-audit-n-series.zh-CN.md) | GM 目标/审批、四语言 presence 读取 API、内存路由 epoch 和告警 YAML |
| [2026-10-07 全量代码审计收尾](20261007-full-code-audit-remediation.zh-CN.md) | UID、Room、错误帧、Mongo CAS、缓存、HTTP/KCP、安全签名、GM、Frame/World 及进程清理 |
| [2026-10-06 用户流程可靠性](20261006-user-flow-reliability.zh-CN.md) | 本地异步/TCP、严格 GM JSON、安全结果、真实 JVM 启停和就绪探针 |
| [2026-10-05 全仓可靠性复核](20261005-full-reliability-review.zh-CN.md) | 本地告警失败重试、Prometheus 单序列最新样本和 API 门禁解释 |
| [2026-10-05 数据、同步与 Kafka 性能修复](20261005-performance-issues.zh-CN.md) | 显式 IO executor、批量/分页、连接池、flush 并发、共享摘要、KCP Cipher 和独占 reply topic |
| [2026-10-04 反馈专项：RPC、网络、数据与边界适配](20261004-feedback-hardening.zh-CN.md) | S1-S3、H1-H10 的确认修复、可配置并发行为、协议限制和未覆盖风险 |
| [2026-10-04 帧同步容量复核](20261004-frame-sync-capacity.zh-CN.md) | `MARK` 消费后释放参与者槽位、非历史策略减少逐帧复制 |
| [2026-10-03 并发、异步与性能](20261003-concurrency-async-performance.zh-CN.md) | 缓存取消/写入准入、ownership fencing、异步异常、drain 与 Room/NPC 热路径 |
| [脚手架首次运行路径（2026-10-03）](20261003-scaffold-onboarding.zh-CN.md) | Maven Wrapper、默认版本、Java 21 前置检查、CLI 错误提示和生成项目上手文档 |
| [线程、缓存与分布式一致性专项审查（2026-09-26）](20260926-concurrency-distributed-audit-20260926.md) | Actor/事件/运行时并发、分层缓存代际与取消、RPC/Kafka/Nacos/Redis 一致性及 World/Frame/Network 边界修复 |
| [质量基线与 Production 装配修复（2026-09-26）](20260926-quality-baseline.md) | 恢复显式 Adapter 选择；强制配置与健康校验、失败不回退的边界验证 |
| [代码审计修复（2026-09-24）](20260924-code-audit-20260924.md) | 既有 GM、网络、数据与游戏域修复；production 禁止 LOCAL 策略已由 9 月 26 日说明纠正 |
| [公开检出 CI 入口（2026-09-23）](20260923-public-ci-gates.md) | 能力/发布材料严格检查、平台事务依赖构建及 Windows Path 继承 |
| [依赖升级与候选合并（2026-09-23）](20260923-dependency-upgrades.md) | MongoDB、Netty、Nacos、Jedis 与 Actions 升级；SpotBugs 4.10 拒绝原因及外部测试边界 |
| [第三轮性能与 IO 资源治理（2026-09-23）](20260923-performance-third.md) | 单帧出站、只读成功 stage、IO 组拥有权与 runtime 可选装配 |
| [codegen 对接（2026-09-23）](20260923-codegen-integration.md) | 只读完整 payload 分发、业务实现保护、四语言增量写入与工具升级步骤 |
| [性能增量（2026-09-23）](20260923-performance-incremental.md) | EventBus/AOI 内部优化、只读输入及生成分发、精确 TopN 缓存 |
| [性能方案 S0-S3（2026-09-23）](20260923-performance-plan.md) | 有界 Actor、直接缓冲、网络预算、排名索引和游戏循环迁移 |
| [2026-09-22 调度、AOI 与编码分配](20260922-performance-feedback.md) | 分段调度、网格边长、同步编码 writer 借用约定与 native 生命周期 |
| [2026-09-22 安全、持久化与异步确认](20260922-security-storage-concurrency.md) | HTTP/Kafka 身份边界、异步提交、Redis 原子更新与缓存并发契约 |
| [2026-09-18 文档现状核对](20260918-documentation-audit.md) | TCP、脚手架、路线图及公开导航校正；无运行时变更 |
| [2026-09-17 缺陷核验修复](20260917-bug-report-verification.md) | 缓存、事件、持久化、玩法组件、协议与 RPC 行为/API 调整 |
| [2026-09-15 平台和生产门禁](20260915-platform-and-production-gates.md) | 平台事务测试、CI job 结论与 artifact 审查边界 |
| [2026-09-15 Kafka 验收与专项审计](20260915-kafka-and-production-audit.md) | 外部前置条件失败时的 blocked 证据与未覆盖专项 |
| [2026-09-15 无 SDK 消费者](20260915-no-sdk-clean-room-and-kafka-blocked.md) | 最小依赖验证及 Kafka blocked 分类 |
| [2026-09-15 Kafka 双进程](20260915-center-logic-kafka-two-process.md) | common-contract、center、logic 与独立 JVM 生命周期 |
| [2026-09-15 配置与运行时组合](20260915-config-lint-and-runtime-combinations.md) | 静态配置检查、空运行时和事件/Actor 组合 |
| [2026-09-15 Adapter 与混合装配](20260915-adapters-and-mixed-composition.md) | 独立 Adapter、实际依赖闭包和混合组件验证 |
| [2026-09-15 Actor 异步线程契约](20260915-actor-async-thread-contract.md) | 调度返回、拒绝、remote IO 与所有权边界 |
| [2026-09-14 TCP 生命周期](20260914-starter-template-tcp-lifecycle.md) | Starter 显式启停、probe 和 net 组件；后续生成入口见快速上手 |
| [2026-09-14 脚手架 ownership](20260914-scaffold-ownership-manifest.md) | 受控文件、冲突检查、升级事务、迁移与回滚 |
| [2026-09-14 业务异步边界](20260914-local-game-async-boundary.md) | local 模板职责划分与 CompletionStage 约束 |
| [2026-09-14 验收证据](20260914-acceptance-evidence.md) | 验收矩阵、CLI 退出码和证据清单 |
| [2026-09-14 文档目录](20260914-documentation-layout.md) | 文档移动/合并、书签与脚本路径调整；无运行时行为变更 |
| [2026-09-14 场景装配](20260914-scenario-composition.md) | runtime 档位、按需 Adapter、诊断和多数据来源绑定 |
| [入口安全](0.1.0-p0-2-entry-security.zh-CN.md) | SecurityChain、认证、重放和 TLS provider 边界 |
| [Adapter 恢复](0.1.0-p0-3-adapter-resilience.zh-CN.md) | 健康、预算、恢复和 fail-fast 语义 |
| [GM 操作](0.1.0-p0-4-gm-operations.zh-CN.md) | GM 审计、幂等和 break-glass 边界 |
| [GM 生产运营](0.1.0-p0-4-gm-production-operations.zh-CN.md) | 生产入口与持久审计边界 |
| [API 兼容](0.1.0-p0-5-api-compatibility.zh-CN.md) | 受保护公共 API baseline 和门禁 |
| [迁移说明模板](template.zh-CN.md) | 新增破坏性变更时复制并填写 |

迁移说明至少写明变更、影响、验证、回滚和未覆盖风险。具体实现仍以[能力矩阵](../capability-matrix.zh-CN.md)和[兼容门禁](../operations/api-compatibility-gate.zh-CN.md)为准。

- [2026-10-08 复查后续：Frame 关闭、Player、生成器与依赖门禁](20261008-audit-followup.zh-CN.md)。
