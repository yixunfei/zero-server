# 易用性基础迁移（2026-09-30，Unreleased）

本次为 0.x 开发改造，不发布或变更现有版本号。

## 需要迁移的行为

- `RuntimeAssemblyPlan` 增加 `configuration` 参数；手工构造计划需提供同次解析的 `RuntimeConfigDiagnostics`，普通 `runtime.plan()` 调用不变。计划和诊断采用值相等语义。
- 新生成项目的 `RuntimeAssembly.diagnose(...)` 统一返回 `RuntimeAssemblyDiagnosis`。读取组件图使用 `diagnosis.plan()`，或使用严格的 `RuntimeAssembly.plan(...)`。所有模板均可通过生成的 `RuntimeAssembly.main` 执行只读诊断。
- 默认配置必须用 `SourcedZeroConfig.withDefaults` 或 `ZeroConfigLoader.loadStandard(defaults)` 表达。程序化输入和文件保持高优先级；系统属性和环境变量现在可以覆盖**应用默认值**。原先依赖默认 enabled/预算遮蔽环境的应用，应改为显式 `MapZeroConfig` 值或清理对应环境变量。
- `ZeroConfig.origin`、`ConfigLookup` 保留来源；Production 的来源类别新增 `file` / `default`。转换成普通 Map 会丢失来源。`ConfigSource.origin` 是输入通道内的来源定位，不改变低层显式来源注册顺序。
- 脚手架 manifest 新增目录身份/格式版本。扩展 JSON 使用 `--catalog`，Java 扩展目录使用命名构造器；升级时必须复用同一目录。未声明目录身份的旧 manifest 仍按已有 ownership 升级规则处理。
- Maven 依赖包含所选 Provider 的 API 依赖闭包，因此纯 runtime POM 会显式列出 `zero-core`；总依赖闭包没有引入网络或驱动。

## 验证与边界

验证命令及结果记录于[任务验证记录](../../tasks/archive/20260930-flexibility-foundation/VERIFY.md)。JDK 21 下相关依赖链最新结果共 820 个测试通过，quality、架构与框架边界守卫通过，四条实际生成项目路径完成编译/测试/运行或只读诊断。新增契约覆盖文件/系统/环境/默认冲突、敏感值遮罩、同次读取稳定性、Provider 与计划有效值一致、CLI 扩展生成、目录冲突和升级一致性。

本轮不改变 RPC、Actor、持久化语义，不新增生产 Adapter；`productionReady=false`。详见 [配置与目录指南](../guides/configuration-and-catalog.zh-CN.md)。
