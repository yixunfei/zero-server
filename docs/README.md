# 文档总览

适用版本：`0.1.0-SNAPSHOT`。先按任务选入口，细节需要时再查。当前能力状态以[能力矩阵](capability-matrix.zh-CN.md)为准，后续工作统一记录在[优化路线图](optimization-roadmap.zh-CN.md)。

## 开始使用

| 我想做什么 | 阅读入口 |
| --- | --- |
| 跑通单体原型、中心—逻辑或外部组件组合 | [快速上手](quickstart.zh-CN.md) |
| 选择玩法模板或可选组件 | [脚手架模板与参数](scaffold-templates.zh-CN.md) |
| 手工选依赖、替换默认实现 | [按需装配](guides/modular-composition-guide.zh-CN.md) |
| 同一业务切换内存、Redis、MongoDB、PostgreSQL | [Repository 接入](guides/repository-composition-guide.zh-CN.md) |
| 查看全部可运行示例 | [示例索引](../examples/README.md) |

## 使用指南

| 主题 | 指南 |
| --- | --- |
| 一命令生成和验证原型 | [原型 Runner 参数](guides/local-prototype-runner.zh-CN.md) |
| 协议工具 | [Codegen 使用](../zero-codegen/docs/user-guide.zh-CN.md)、[`.si` 语法标准](../zero-codegen/docs/protocol-dsl.zh-CN.md) |
| 网络收发 | [Netty 到生成 BO 的完整链路](guides/net-full-flow.zh-CN.md) |
| RPC | [共享接口](../zero-rpc-common/USER-GUIDE.zh-CN.md)、[路由与客户端](../zero-rpc/USER-GUIDE.zh-CN.md)、[Kafka](../zero-rpc-kafka/USER-GUIDE.zh-CN.md)、[Nacos](../zero-discovery-nacos/README.zh-CN.md) |
| 配置热更 | [CSV 配置与原子替换](guides/csv-config-hot-reload.zh-CN.md) |
| 定时业务 | [受管 Scheduler](guides/managed-scheduler.zh-CN.md) |
| 日志与监控 | [可观测性运行时](guides/observability-runtime.zh-CN.md) |

## 架构与参考

| 主题 | 当前说明与详细契约 |
| --- | --- |
| 产品和模块 | [产品目标](product-design.zh-CN.md)、[总体架构](architecture.zh-CN.md)、[模块图](module-map.md) |
| 装配 | [运行时装配设计](reference/modular-runtime-assembly.zh-CN.md) |
| 业务状态 | [事件模型](event-model.zh-CN.md)、[线程模型](threading-model.zh-CN.md) |
| 协议与传输 | [协议设计](protocol-dsl.zh-CN.md)、[RPC 设计](rpc.zh-CN.md)、[生产连接生命周期](reference/production-network-lifecycle-contract.zh-CN.md) |
| 数据与运行 | [数据和缓存](data-cache.zh-CN.md)、[Adapter 启动失败与回滚](reference/production-adapter-failfast-contract.zh-CN.md) |
| 运营 | [GM 接入](gm-admin.zh-CN.md)、[GM 授权契约](reference/gm-operation-context-contract.zh-CN.md)、[标准入口和审计存储](reference/gm-standard-entry-persistence-contract.zh-CN.md) |
| 观测与更新 | [日志和监控设计](logging-observability.zh-CN.md)、[最小字段契约](reference/observability-minimum-field-contract.zh-CN.md)、[热更边界](hot-update.zh-CN.md) |

玩法组件的本地容量、拒绝策略和验证口径已并入对应契约：

| 组件 | 契约与本地验证边界 |
| --- | --- |
| 房间 | [Room](reference/room-component-minimum-contract.zh-CN.md) |
| AOI / 状态同步 | [AOI / State Sync](reference/aoi-state-sync-minimum-contract.zh-CN.md) |
| 帧同步 | [Frame Sync](reference/frame-sync-minimum-contract.zh-CN.md) |
| NPC 调度 | [NPC Tick](reference/npc-tick-minimum-contract.zh-CN.md) |
| 排行榜 / 赛季 | [Ranking](reference/ranking-season-minimum-contract.zh-CN.md) |
| 世界 / 分片 | [World / Shard](reference/world-shard-minimum-contract.zh-CN.md) |

## 验证、运维与贡献

| 任务 | 文档 |
| --- | --- |
| 构建与协作 | [贡献指南](../CONTRIBUTING.md)、[代码规范](code-style.zh-CN.md)、[Git 工作流](git-workflow.zh-CN.md) |
| 本地验证 | [Stage 0 验收](operations/local-stage0-acceptance.zh-CN.md)、[架构守卫](operations/architecture-guard.zh-CN.md) |
| API 变更检查 | [API 基线与兼容门禁](operations/api-compatibility-gate.zh-CN.md)、[基线文件](api-baseline/README.md) |
| 性能验证 | [方法与基准](operations/performance.zh-CN.md)、[本地性能门禁](operations/local-performance-gate.zh-CN.md) |
| 部署和恢复 | [Compose 基线](operations/deployment-baseline.zh-CN.md)、[备份恢复 Runbook](operations/backup-recovery-runbook.zh-CN.md) |
| 发布准备 | [材料检查](operations/release-hardening-readiness.zh-CN.md)、[发布检查单](operations/release-checklist.zh-CN.md)、[迁移记录与模板](migrations/README.md) |
| 安全与社区 | [安全策略](../SECURITY.md)、[行为准则](../CODE_OF_CONDUCT.md) |

## 当前状态与历史记录

- [能力矩阵](capability-matrix.zh-CN.md)：当前实现、可用范围和未证明项。
- [优化路线图](optimization-roadmap.zh-CN.md)：尚待实施的工作、优先级和验收条件。
- [Changelog](../CHANGELOG.md)：用户可见的版本变更。
- [迁移记录](migrations/README.md)：已有项目需要调整的行为和 API。
- [历史审查与性能报告](reports/README.md)：指定日期、环境和代码范围的证据，不能代替当前验证。

## 文档维护方式

一个主题只保留一个当前入口。README 负责项目介绍；快速上手负责可执行流程；使用指南解释接线与配置；参考文档保存契约和设计；运维文档说明验证方法；报告记录历史事实。产品目标和候选设计必须标明适用边界，不能当作已实现能力。

新增或移动页面时同步本索引、相对链接、示例说明及读取该文件的工具路径。公开文档只引用仓库跟踪文件；本机任务、缓存和临时日志不能作为新用户必须读取的材料。历史验证应附日期、范围与复现命令。

2026-09-18 核对更新了 TCP、脚手架升级、能力状态与缺陷修复导航；发现 net 生成工程当前编译受阻，详细结果见[本轮核对报告](reports/documentation-audit-20260918.zh-CN.md)。后续维护须同时区分源码存在、构建通过、外部环境验收与生产就绪。

2026-09-14 整理说明：原场景接入页并入快速上手，API 稳定性说明并入兼容门禁，六份容量说明并入组件契约；装配推进记录和场景审查移入 `reports/`。完整对应关系见[文档路径调整](migrations/20260914-documentation-layout.md)。旧内容保留在 Git 历史，后续维护以上述入口为准。
