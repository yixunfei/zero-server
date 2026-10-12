# 模块接入示例迁移说明

后续审核已修正示例实现和验证边界；当前用法、示例 API/schema 迁移及最新证据见 [examples 审核修复](20261012-examples-review.zh-CN.md)。下文保留首次新增时的记录。

## 变更

新增 `examples/module-access` 集中接入目录，为 62 个 `zero-*` 模块和根 `examples` 场景入口提供 63 张接入卡片。

每张卡片包含：

- Maven 坐标或 Parent/BOM 的正确装配位置。
- 当前模块的核心 API 入口。
- 可复制的最小装配片段。
- 对应可运行场景和验证命令。
- 外部 Adapter、远程 IO、线程和资源关闭边界。

各模块原有 `examples/README.md` 保留原有说明，并新增对应卡片链接。

## 影响边界

本次只新增文档索引和接入片段，不修改生产代码、公共 API、协议格式、依赖方向或运行时行为。Parent、BOM 和 runtime provider 卡片明确说明其构建/装配职责，不建议作为业务模块直接调用。

## 验证

```powershell
Get-ChildItem examples/module-access -Filter *.md
git diff --check
java scripts/ZeroArchitectureGuard.java
```

具体能力验证仍以每张卡片链接的场景命令为准；外部中间件由已有 Compose 场景显式启动和清理。
