# 文档目录调整（2026-09-14）

适用：`0.1.0-SNAPSHOT`。本次只调整说明、导航和读取文档的验证脚本路径，不改变运行时 API、配置、协议或存储格式，无数据迁移。

## 新入口

从[文档总览](../README.md)进入。根 README 只保留介绍和最短运行方式；[快速上手](../quickstart.zh-CN.md)统一三种场景；[能力矩阵](../capability-matrix.zh-CN.md)记录当前状态；[路线图](../optimization-roadmap.zh-CN.md)只列后续工作。

## 移动路径

下面的旧路径均相对于 `docs/`。外部书签、脚本或项目说明若引用这些路径，需要更新目标；仓库内链接与文档验证工具已同步。

| 旧文件 | 新位置 |
| --- | --- |
| `modular-composition-guide.zh-CN.md` | [guides/modular-composition-guide.zh-CN.md](../guides/modular-composition-guide.zh-CN.md) |
| `repository-composition-guide.zh-CN.md` | [guides/repository-composition-guide.zh-CN.md](../guides/repository-composition-guide.zh-CN.md) |
| `csv-config-hot-reload.zh-CN.md` | [guides/csv-config-hot-reload.zh-CN.md](../guides/csv-config-hot-reload.zh-CN.md) |
| `managed-scheduler.zh-CN.md` | [guides/managed-scheduler.zh-CN.md](../guides/managed-scheduler.zh-CN.md) |
| `net-full-flow.zh-CN.md` | [guides/net-full-flow.zh-CN.md](../guides/net-full-flow.zh-CN.md) |
| `observability-runtime.zh-CN.md` | [guides/observability-runtime.zh-CN.md](../guides/observability-runtime.zh-CN.md) |
| `local-prototype-runner.zh-CN.md` | [guides/local-prototype-runner.zh-CN.md](../guides/local-prototype-runner.zh-CN.md) |
| `modular-runtime-assembly.zh-CN.md` | [reference/modular-runtime-assembly.zh-CN.md](../reference/modular-runtime-assembly.zh-CN.md) |
| `production-network-lifecycle-contract.zh-CN.md` | [reference/production-network-lifecycle-contract.zh-CN.md](../reference/production-network-lifecycle-contract.zh-CN.md) |
| `production-adapter-failfast-contract.zh-CN.md` | [reference/production-adapter-failfast-contract.zh-CN.md](../reference/production-adapter-failfast-contract.zh-CN.md) |
| `observability-minimum-field-contract.zh-CN.md` | [reference/observability-minimum-field-contract.zh-CN.md](../reference/observability-minimum-field-contract.zh-CN.md) |
| `gm-operation-context-contract.zh-CN.md` | [reference/gm-operation-context-contract.zh-CN.md](../reference/gm-operation-context-contract.zh-CN.md) |
| `gm-standard-entry-persistence-contract.zh-CN.md` | [reference/gm-standard-entry-persistence-contract.zh-CN.md](../reference/gm-standard-entry-persistence-contract.zh-CN.md) |
| `room-component-minimum-contract.zh-CN.md` | [reference/room-component-minimum-contract.zh-CN.md](../reference/room-component-minimum-contract.zh-CN.md) |
| `aoi-state-sync-minimum-contract.zh-CN.md` | [reference/aoi-state-sync-minimum-contract.zh-CN.md](../reference/aoi-state-sync-minimum-contract.zh-CN.md) |
| `frame-sync-minimum-contract.zh-CN.md` | [reference/frame-sync-minimum-contract.zh-CN.md](../reference/frame-sync-minimum-contract.zh-CN.md) |
| `npc-tick-minimum-contract.zh-CN.md` | [reference/npc-tick-minimum-contract.zh-CN.md](../reference/npc-tick-minimum-contract.zh-CN.md) |
| `ranking-season-minimum-contract.zh-CN.md` | [reference/ranking-season-minimum-contract.zh-CN.md](../reference/ranking-season-minimum-contract.zh-CN.md) |
| `world-shard-minimum-contract.zh-CN.md` | [reference/world-shard-minimum-contract.zh-CN.md](../reference/world-shard-minimum-contract.zh-CN.md) |
| `architecture-guard.zh-CN.md` | [operations/architecture-guard.zh-CN.md](../operations/architecture-guard.zh-CN.md) |
| `deployment-baseline.zh-CN.md` | [operations/deployment-baseline.zh-CN.md](../operations/deployment-baseline.zh-CN.md) |
| `backup-recovery-runbook.zh-CN.md` | [operations/backup-recovery-runbook.zh-CN.md](../operations/backup-recovery-runbook.zh-CN.md) |
| `local-performance-gate.zh-CN.md` | [operations/local-performance-gate.zh-CN.md](../operations/local-performance-gate.zh-CN.md) |
| `local-stage0-acceptance.zh-CN.md` | [operations/local-stage0-acceptance.zh-CN.md](../operations/local-stage0-acceptance.zh-CN.md) |
| `performance.zh-CN.md` | [operations/performance.zh-CN.md](../operations/performance.zh-CN.md) |
| `release-checklist.zh-CN.md` | [operations/release-checklist.zh-CN.md](../operations/release-checklist.zh-CN.md) |
| `release-hardening-readiness.zh-CN.md` | [operations/release-hardening-readiness.zh-CN.md](../operations/release-hardening-readiness.zh-CN.md) |
| `api-compatibility-gate.zh-CN.md` | [operations/api-compatibility-gate.zh-CN.md](../operations/api-compatibility-gate.zh-CN.md) |
| `migration-guide-template.zh-CN.md` | [migrations/template.zh-CN.md](template.zh-CN.md) |
| `demand-driven-composition.zh-CN.md` | [reports/demand-composition-2026-09-12.zh-CN.md](../reports/demand-composition-2026-09-12.zh-CN.md) |
| `scenario-audit.zh-CN.md` | [reports/scenario-audit-2026-09-14.zh-CN.md](../reports/scenario-audit-2026-09-14.zh-CN.md) |

## 合并内容

| 原页面 | 当前入口 |
| --- | --- |
| `scenario-onboarding.zh-CN.md` | [quickstart.zh-CN.md](../quickstart.zh-CN.md) |
| `api-stability.zh-CN.md` | [operations/api-compatibility-gate.zh-CN.md](../operations/api-compatibility-gate.zh-CN.md) |
| `room-component-capacity.zh-CN.md` | [reference/room-component-minimum-contract.zh-CN.md](../reference/room-component-minimum-contract.zh-CN.md#本地容量与验证) |
| `aoi-state-sync-capacity.zh-CN.md` | [reference/aoi-state-sync-minimum-contract.zh-CN.md](../reference/aoi-state-sync-minimum-contract.zh-CN.md#本地容量与验证) |
| `frame-sync-capacity.zh-CN.md` | [reference/frame-sync-minimum-contract.zh-CN.md](../reference/frame-sync-minimum-contract.zh-CN.md#本地容量与验证) |
| `npc-tick-capacity.zh-CN.md` | [reference/npc-tick-minimum-contract.zh-CN.md](../reference/npc-tick-minimum-contract.zh-CN.md#本地容量与验证) |
| `ranking-season-capacity.zh-CN.md` | [reference/ranking-season-minimum-contract.zh-CN.md](../reference/ranking-season-minimum-contract.zh-CN.md#本地容量与验证) |
| `world-shard-capacity.zh-CN.md` | [reference/world-shard-minimum-contract.zh-CN.md](../reference/world-shard-minimum-contract.zh-CN.md#本地容量与验证) |

六份组件说明优先显示当前本地接口、容量与示例；原设计候选保存在折叠区域，不能当作现行 API。历史审查按日期移入 `reports/`；迁移记录保持可查。

## 内容修正与验证

修正了 GM 授权/REST 适配、Prometheus HTTP Endpoint、世界分片本地实现的过时描述，区分已存在接口与尚未完成的真实传输、持久化或生产验证；修正生成协议目录、外部 runtime 默认诊断、消费者数量及不可执行命令。

验证基于 Git 中可公开的文件集合：检查相对链接、章节锚点和索引可达性，在只含公开文件的检出副本中执行架构及受影响材料检查。材料检查使用 `--allow-missing-evidence` 时允许缺少未公开的维护者证据，不表示这些证据通过；本次文档检查不替代运行时测试。

旧内容可从 Git 历史追溯。若需撤回整理，可还原本次文档变更，并同步还原两个材料验证脚本中的路径与文档标记；应用数据不受影响。
