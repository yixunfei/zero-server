# 2026-09-26 质量基线与 Production 按需装配修复

适用版本：`0.1.0-SNAPSHOT`。本次恢复生产装配的既有按需选择契约，不新增公共 API、配置键或数据格式。

## 变化与影响

9 月 24 日新增的 production 全局限制把 data/cache/RPC 六类能力的 LOCAL provider 全部禁止，其中包括当前唯一的 `PersistenceManager` 实现。完整 Starter 的基础组件图仍需要它，因此默认组合和启用外部 Adapter 的组合都会在规划阶段失败；默认测试出现 2 项断言失败、8 项错误。

`ProductionAssembly` 现在使用不预设关键能力集合的 production profile：

- Adapter 继续由显式 selector 启用；未启用的槽位可保留本地实现。
- 显式启用的外部组件仍须通过必填配置、创建、启动健康和预算校验；失败终止构建或启动，不回退本地实现。
- 需要项目级关键能力限制时，使用中立 `RuntimeComposition` 与 `RuntimeProfile.production(criticalCapabilities)` 或 `productionByCapabilityId(...)`，并显式提供符合策略的组件图。

`production` 是启动校验档位，不表示默认使用持久存储，也不表示生产就绪。持久化、分布式缓存和跨进程 RPC 必须按应用需求显式配置。`productionReady=false` 保持不变。

## 迁移与回滚

现有按需组合不需要修改代码或配置；重新构建框架并更新本地 SNAPSHOT 即可。未基于 9 月 24 日回归版本开发的应用，无额外迁移步骤。

如果应用曾依赖该版本的全局拒绝来检查部署配置，需要把必要组件校验显式放入自己的装配政策，不能继续仅凭 `production` 名称判断是否启用了外部存储。

回滚本次运行时变更会恢复上述装配失败。若必须回滚，应恢复 `ProductionAssembly.composition` 的旧规则并重新运行完整门禁；没有数据迁移或数据回滚步骤。

## 验证

本轮环境为 Windows 11、JDK 21.0.4、Maven 3.9.8。验证命令、结果与未覆盖范围统一记录于[质量报告](../reports/quality-baseline-20260926.zh-CN.md)。

新增边界测试验证外部组件缺少健康声明时不创建任何 provider、创建失败时回收已登记资源、健康失败时终止启动且重复关闭不会重复释放；这些路径都验证本地 provider 未被创建。既有默认 Starter 测试扩展到实际 `start()`。

本轮不涉及真实中间件故障恢复、跨平台复测、性能或长稳；本地通过不构成这些场景的证明。
