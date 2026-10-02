# 配置诊断与脚手架元数据统一（2026-09-30）

## 变更

- `RuntimeAssembler.Builder.diagnoseConfiguration()` 与 `RuntimeComposition.diagnoseConfiguration()` 返回不可变的 `RuntimeConfigDiagnostics`。
- 每个 typed key 的诊断包含 owner、类型、来源类别/来源 ID、alias、解析状态、reloadability 和有效值；敏感值固定显示为 `***`，复杂对象只显示类型占位。
- 配置错误摘要增加安全的来源定位（类别、来源 ID、alias），不会包含原始输入或 decoder 异常文本。
- 新增 `ScaffoldComponentDefinition`，脚手架组件的 Provider、额外 Maven 坐标、安装表达式和配置样例由同一元数据定义驱动。
- `ScaffoldComponents` 增加显式定义目录构造入口，重复组件 ID、重复 Provider 和未知 Provider 在生成前失败。

## 迁移

现有调用无需修改。需要检查启动配置时，可在 `build()` 前调用：

```java
RuntimeConfigDiagnostics diagnostics = composition.diagnoseConfiguration();
```

脚手架扩展方应构造 `ScaffoldComponentDefinition` 并传入 `new ScaffoldComponents(model, definitions)`；组件定义必须包含 `bootstrap`，Provider 必须先存在于 `RuntimeCapabilityModel`。标准 CLI 仍使用内置目录，不会自动扫描 classpath。

## 验证

- Java 21 下 `zero-runtime` 相关依赖链测试通过，runtime 58 个测试通过。
- Java 21 下 `zero-codegen` 相关依赖链测试通过，codegen 79 个测试通过。
- 新增配置敏感值遮罩、optional 缺省状态、自定义 Provider 坐标/表达式和目录冲突测试。
- Docker 外部 Adapter 验证记录在任务 `VERIFY.md`；Docker 不可用时必须保留未验证说明。

## 未覆盖风险

本变更不提供 Player、Room、World 的跨进程 owner、租约、epoch、迁移、恢复或容量语义；外部 Adapter 的生产容量和故障恢复仍需真实环境专项验证。
