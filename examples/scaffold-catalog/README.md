# 显式 catalog 最小示例

`greeting.catalog.json` 使用现有 runtime API 注册一个字符串能力，无需额外类或驱动。

先用 JDK 21 构建本地开发坐标，再执行 [配置与目录指南](../../docs/guides/configuration-and-catalog.zh-CN.md) 中的命令。生成项目的 `RuntimeAssembly` 是唯一装配入口，应用可通过 `ComponentKey.single("sample.greeting", String.class)` 获取该能力。

该 JSON 是可信的代码生成输入，不是运行时远程配置。catalog 版本为 1，生产就绪为 false。
