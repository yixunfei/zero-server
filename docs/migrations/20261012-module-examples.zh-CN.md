# 2026-10-12 模块示例与使用说明

后续审核已修正示例实现和验证边界；当前用法、示例 API/schema 迁移及最新证据见 [examples 审核修复](20261012-examples-review.zh-CN.md)。下文保留首次新增时的记录。

## 变更

为根 Reactor 中的全部 `zero-*` 模块增加 `examples/README.md`。直接供业务使用的模块提供 API 入口、依赖片段和本地/生产选择说明；Parent、BOM、runtime provider 和 Adapter 模块说明其装配边界并链接到已有可运行场景。根 `examples/README.md` 增加完整模块索引。

## 影响

这是文档和示例入口变更，不改变 Java 公共 API、协议、线程模型、运行时行为、依赖方向或构件版本。示例默认使用本地/in-memory 能力；Kafka、Nacos、MongoDB、Redis、PostgreSQL 等外部服务仍需应用显式提供隔离环境。

## 使用方式

1. 打开目标模块的 `examples/README.md`，确认模块职责和可选 Adapter。
2. 复制链接场景中的最小业务流程，并按 README 中的依赖选择加入 POM。
3. 在仓库根目录先安装当前源码，再运行场景 README 给出的 Maven 命令。

## 验证

- 62 个 `zero-*` 模块均存在 `examples/README.md`。
- 模块 README 的相对链接均指向存在的目录或文件。
- 现有根示例未改动运行逻辑；受影响场景使用各自 Maven 测试命令验证。
- `git diff --check` 通过。

## 回滚

删除各模块新增的 `examples/README.md`、根示例索引新增段落及本迁移说明即可；不需要数据或配置回滚。

## 未覆盖风险

示例不替代生产安全、容量、故障恢复和长稳验证。外部 Adapter 的真实连接、网络故障和恢复场景仍应在隔离测试环境中显式运行。
