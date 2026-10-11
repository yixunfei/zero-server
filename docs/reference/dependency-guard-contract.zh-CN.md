# 仓库依赖门禁

`java scripts/ZeroArchitectureGuard.java` 在原源码边界检查之外执行 `ZeroDependencyGuard`，子检查非零退出会使架构门禁失败。CI 同时执行 `java scripts/VerifyDependencyGuard.java` 的隔离正负夹具。

## 模型与审核清单

[`module-dependencies.tsv`](module-dependencies.tsv) 记录全部节点和实际依赖边。当前包含 63 个 POM 节点（根、61 个默认模块、1 个 benchmark）及 259 条去重声明边，包含继承的 test 依赖。

- `module` 行：POM 路径、group:artifact:version、默认/profile 模块声明来源。
- `edge` 行：消费模块 POM、目标坐标、scope、optional、type、classifier、实际声明 POM/profile。dependencyManagement 只为实际声明补默认值，不直接构成依赖边。
- 读取仓内父链、仓内 BOM 和属性。对相关 profile 枚举组合并取保守并集；不根据本机 OS 只检查当前激活项。epoll 两个 classifier 分开记录。
- compile/runtime/provided/system 都参与声明模块的生产硬约束及环检测。test 不参与生产图。optional 仍约束声明模块自身；消费者闭包不自动传播下层 optional/provided/system 边。
- 核心/基础模块依赖、驱动边界、集成模块闭包及生产环独立于清单；同步改清单不能豁免违规。

合法依赖变更须同时修改模块图和清单并审核。`java scripts/ZeroDependencyGuard.java --print` 仅向标准输出打印候选清单，仍执行硬约束；不会写入或自动接受清单。普通检查发现增删、坐标或来源变化时失败。

## 明确边界

这是本仓静态模型，不是完整 Maven effective model 或第三方传递供应链审计。外部父/BOM、带相关 profile 的导入 BOM、未解析关键属性、超过 128 种相关 profile 组合会明确拒绝，须扩展模型与回归后再接入。第三方 exclusions、版本冲突调解不用于放宽本仓硬约束，闭包采取保守判断。生产同模块环即使某些 profile 在特定部署中互斥，也被拒绝。

夹具在 `target/dependency-guard-verify/<uuid>` 保留变异 POM 和拒绝原因；不修改工作树 POM。覆盖原 profile/parent/cycle 三项漏检、继承 JUnit、management-only、optional、scope、缺模块、未解析属性、profile 属性/管理覆盖及清单漏行。
