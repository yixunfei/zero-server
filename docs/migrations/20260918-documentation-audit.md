# 2026-09-18 文档现状核对

适用版本：`0.1.0-SNAPSHOT`。本轮基于当前工作区源码、模板、测试和脚本更新说明，不改变运行时 API、配置、协议或数据格式。

## 文档调整

- 快速上手及模板目录区分默认一次性 smoke、`runtime + net` 策略装配与 `local + net` 显式 TCP Server；实测发现 net 生成装配编译失败，补充复现命令并指向已通过的真实 TCP 示例。
- 能力矩阵和路线图承认已存在的 TCP 生命周期、脚手架升级事务与 Kafka 三模块示例，同时保留真实 broker、生产治理和容量尚未证明的边界。
- 补齐迁移、报告及示例索引，修正公开报告对维护者本机任务文件的依赖，提供公开复现命令。
- Changelog 去除重复条目，将 9 月 17 日修复归入 Unreleased；历史报告与迁移中的旧验证结论仍按原日期解读。
- 延续 9 月 14 日的目录分类，没有进一步移动或删除公开页面。旧路径对照仍见[文档目录迁移](20260914-documentation-layout.md)。

## 升级与回滚

读者无需迁移数据库或重新生成项目即可使用新的文档。复现 net 生成问题时应新建输出目录；已有工程升级遵守[ownership 迁移规则](20260914-scaffold-ownership-manifest.md)，不能使用 `--force` 绕过手工修改冲突。

本轮说明更正可独立回退；不要因此回退已有业务修复。此前修复的行为变化和回滚限制见[9 月 17 日迁移说明](20260917-bug-report-verification.md)。

## 验证口径

文档链接检查仅覆盖可提交文件、本地路径与 Markdown 标题锚点；不证明远端 URL 可用。构建测试、生成工程与真实 loopback TCP 验证的最终结果记录于[本次整理报告](../reports/documentation-audit-20260918.zh-CN.md)；历史 CI job、外部 broker 和性能结果不算作本轮重新执行。

<!-- zero-migration-verification-and-rollback=required -->
