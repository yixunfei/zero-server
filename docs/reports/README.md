# 历史报告

报告按日期记录一次审查或基准的范围、环境和结论。它们用于追溯决策，不能替代当前源码、测试和[能力矩阵](../capability-matrix.zh-CN.md)。

| 报告 | 用途 |
| --- | --- |
| [产品目标修复与玩家链路验收（2026-10-14）](20261014-product-goal-remediation.zh-CN.md) | D01–D08、类型化异步协议/可信 Actor 上下文、绑定保存、档案及奖励恢复；全仓质量、26 生成组合、真实数据库/Kafka 和未测范围 |
| [通用适配与低心智开发目标二轮复查（2026-10-12）](20261012-product-goal-recheck.zh-CN.md) | 交叉反证与五组本地复现；缓存无效选项、排行榜生命周期边界、仓库批量错误码及前轮结论范围校正 |
| [通用适配与低心智开发目标复审（2026-10-12）](20261012-product-goal-review.zh-CN.md) | 62 模块逐项分析、公开入口与 NPC 问题复核、业务组合/定制/运营差距及分阶段验收建议 |
| [全仓可靠性复核（2026-10-05）](full-reliability-review-20261005.zh-CN.md) | 61 模块质量与契约门禁、监控故障注入、真实中间件验证及剩余边界 |
| [数据、同步与 Kafka 性能评估（2026-10-05）](performance-issues-20261005.zh-CN.md) | H1-H8 真实性、设计/安全边界、修复、真实中间件验证与未测容量 |
| [反馈专项：RPC、网络、数据与边界适配（2026-10-04）](feedback-hardening-20261004.zh-CN.md) | S1-S3、H1-H10 的复现确认、修复证据、验证结果和剩余风险 |
| [并发与性能复核（2026-10-04）](concurrency-performance-followup-20261004.zh-CN.md) | 现有修复门禁复核、帧同步容量新增缺陷及验证边界 |
| [并发、异步与性能专项审计（2026-10-03）](concurrency-async-performance-20261003.zh-CN.md) | 并发回归、资源容量、ownership fencing、生命周期关闭及 Room 分配证据 |
| [线程、缓存与分布式一致性专项审查（2026-09-26）](concurrency-distributed-audit-20260926.zh-CN.md) | 全仓并发、缓存、RPC/发现、Redis 及游戏状态边界的确认缺陷、修复和门禁结果 |
| [质量基线与阻塞修复（2026-09-26）](quality-baseline-20260926.zh-CN.md) | 当前工作区测试基线、Production 按需装配回归与验证边界 |
| [源码审计（2026-09-24）](code-audit-20260924.zh-CN.md) | 并发、安全、数据面、游戏域的历史候选；未跑测试，后续已有修复及结论纠正 |
| [第三轮性能与 IO 治理（2026-09-23）](performance-third-20260923.zh-CN.md) | 单帧/AOI/身份/事件完成对照、条件候选取舍、共享 IO 生命周期与生成 DTO 负载 |
| [性能增量（2026-09-23）](performance-incremental-20260923.zh-CN.md) | 共享竞争、多观察者、只读 payload 和批量搬移对照；契约与未实施项 |
| [性能方案 S0-S3（2026-09-23）](performance-plan-20260923.zh-CN.md) | JMH 前后对照、独立 TCP 负载、资源与验证边界 |
| [性能反馈优化（2026-09-22）](performance-feedback-20260922.zh-CN.md) | Actor 分段、AOI 网格、编码分配和 FFM 决策；本地对照基准与验证边界 |
| [文档现状核对（2026-09-18）](documentation-audit-20260918.zh-CN.md) | 当前源码/文档核对、验证结果与 net 生成编译限制 |
| [缺陷核验与修复（2026-09-17）](bug-analysis-verification-20260917.zh-CN.md) | 原报告逐项纠正、回归测试和 0.x 行为调整；历史工作区证据 |
| [场景接入审查（2026-09-14）](scenario-audit-2026-09-14.zh-CN.md) | 三种服务器形态、依赖裁剪、已修复问题和后续路线 |
| [按需组装审查（2026-09-07）](composition-review-2026-09-07.zh-CN.md) | 早期问题发现与修复过程 |
| [按需组装推进记录（2026-09-12）](demand-composition-2026-09-12.zh-CN.md) | 实施批次和验证记录 |
| [协议编解码对比](protocol-codec-comparison.zh-CN.md) | Zero Binary Protocol、Protobuf、FlatBuffers 的基准方法 |

新报告应注明日期、提交、JDK/Maven、命令、结果和未验证项；当前入口或能力状态变化应同步更新 `docs/` 主题文档，而不是只修改历史报告。
