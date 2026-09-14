# zeroServer 架构守卫

本文用于把 `docs/module-map.md` 中的模块依赖边界变成可重复运行的只读检查。

`scripts/ZeroArchitectureGuard.java` 面向框架贡献者和新用户，用 Java 21 source-file 模式读取根 `pom.xml`、关键模块 `pom.xml` 和 `docs/module-map.md`，确认当前仓库仍然遵守核心分层约束。它不会修改 POM，不会创建模块，不会启动 Docker，也不会连接 Kafka / MongoDB / Redis / PostgreSQL / Nacos。

## 1. 运行方式

在仓库根目录运行：

```powershell
java scripts/ZeroArchitectureGuard.java
```

帮助信息：

```powershell
java scripts/ZeroArchitectureGuard.java --help
```

正常摘要类似：

```text
zero-architecture-guard=ok|modules=56|rules=20|violations=0|warnings=0
```

其中：

- `modules` 表示当前守卫关注的根 Maven reactor 模块数量。
- `rules` 表示本次执行的架构边界规则数量。
- `violations` 表示明确违反架构边界的规则数量。
- `warnings` 表示需要关注但不直接阻断的提醒数量，例如新增 `zero-*` 模块但尚未纳入守卫规则。

## 2. 检查内容

### 2.1 仓库根与模块清单

脚本会检查：

- 当前目录是否包含 `pom.xml`、`CONTRIBUTING.md` 和 `docs/module-map.md`。
- 根 `pom.xml` 是否声明当前 54 个预期模块。
- 每个预期模块是否存在 `pom.xml`。

当前守卫关注的模块包括 `zero-core`、`zero-runtime`、`zero-event`、`zero-protocol`、`zero-actor`、`zero-game`、`zero-player`、`zero-scene`、`zero-net`、`zero-rpc`、`zero-data`、`zero-cache`、`zero-log`、`zero-monitor`、`zero-gm`、`zero-hot-update`、`zero-server-starter` 和 `zero-server-starter-production` 等。

### 2.2 核心与基础模块边界

脚本会确认：

- `zero-core` 不声明直接 dependencies。
- `zero-runtime` 的非测试直接依赖恰好只有 `group.zn.zero:zero-core`。
- `zero-event`、`zero-protocol`、`zero-actor` 当前只依赖 `zero-core`。
- `zero-actor` 不直接绑定 RPC、Kafka、Nacos、Redis、MongoDB、PostgreSQL 或 Netty。

这些规则保护核心内核和 Actor 调度路径，避免具体基础设施或上层语义反向污染底层。

### 2.3 RPC、数据和 Adapter 边界

脚本会确认：

- `zero-rpc` 不直接依赖 `zero-rpc-kafka`、`zero-discovery-nacos`、Kafka client 或 Nacos SDK。
- `zero-data` 不依赖 `zero-actor`。
- `zero-server-starter` 的 compile/runtime 依赖不包含真实 Adapter 模块；测试 scope 允许用于 smoke 或 external-test 验证。
- `zero-server-starter-production` 显式依赖全量本地 Starter 和独立的 Kafka、MongoDB、Redis、PostgreSQL、Nacos、network runtime 集成模块。
- `integration-boundaries` 递归检查 `zero-runtime-*`、中立 discovery 与 RPC discovery 的仓内 compile/runtime 依赖闭包，禁止反向依赖 Starter 或引入无关 Adapter。

这些规则保护 local 默认路径和 production opt-in 路径的差异：本地 starter 负责无 Docker、无真实中间件的开箱体验；真实 Adapter 必须由 production starter 或业务项目显式接入。

### 2.4 可观测性与 benchmark 叶子边界

O1 后新增的五项守卫会确认：

- `zero-core`、`zero-net` 不反向依赖 `zero-log` / `zero-monitor`，`zero-monitor` 不反向依赖 `zero-log`。
- 除 `zero-log` 自身与明确列出的顶层装配文件外，正式运行时、示例和脚手架源码只使用 `LogAppender`，不能直接持有 terminal `LogSink`。
- 非 benchmark 模块不依赖 `zero-benchmarks`，也不引入 JMH artifact。
- `zero-benchmarks` 不进入默认 reactor，只能由无自动激活条件的根 `benchmarks` profile 显式启用。
- `zero-benchmarks` 只依赖冻结的运行时模块集合和 JMH 1.37；它是证据叶子，不允许运行时模块反向依赖。

这些规则保护安全日志入口和默认构建成本，但不代表 production sink、性能阈值、容量或长稳已经完成。

### 2.5 模块图文档锚点

脚本会检查 `docs/module-map.md` 是否包含关键模块和 Adapter 锚点，例如：

- `zero-core`
- `zero-runtime`
- `zero-server-starter`
- `zero-server-starter-production`
- `zero-rpc`
- `zero-actor`
- `zero-data`
- `zero-rpc-kafka`
- `zero-discovery-nacos`
- `zero-benchmarks`
- `LogAppender`
- `LogSink`
- `Kafka`
- `Nacos`

该检查不解析 Markdown 语义，只确认关键边界仍可在模块图中定位。

## 3. 不证明什么

架构守卫不证明：

- Maven 全量测试通过。
- quality profile、PMD、SpotBugs、Checkstyle 或 JaCoCo 通过。
- external-tests 能连接真实中间件。
- Maven 最终解析的第三方传递依赖完全符合预期；此项由 `examples/modular-composition` 的 Enforcer 与实际 classpath 测试补充。
- Java import/package 层没有越界引用。
- 生产容量、长稳、压测或性能基线已经完成。
- 公共 API、SPI、协议、线程模型、存储格式、日志字段或权限模型已经冻结。

它只是一个轻量、可重复、低风险的静态边界检查入口。

## 4. 失败时怎么处理

如果输出 `zero-architecture-guard=failed`：

1. 先阅读失败行中的模块、依赖和规则名称。
2. 判断这是误报、文档未同步，还是确实引入了反向依赖。
3. 如果只是新增低风险文档或脚本入口，补充守卫规则或文档即可。
4. 如果需要修改模块依赖方向、移动包结构、引入真实 Adapter、调整公共 API 或创建正式能力模块，应先通过 GitHub Design Proposal 说明设计、兼容性、性能、安全与验证边界，并等待维护者评审。

不要为了让脚本通过而悄悄把真实 Adapter 加回 `zero-core`、`zero-actor`、`zero-rpc` 或 local starter 路径。

## 5. 推荐工作流

新用户：

```text
ZeroLocalDoctor
  -> ZeroArchitectureGuard
  -> quickstart
  -> RunLocalPrototype --fromKeywords "<需求关键词>"
```

框架贡献者：

```text
ZeroArchitectureGuard
  -> docs/module-map.md
  -> GitHub Issue / Design Proposal
  -> 维护者评审
  -> focused implementation
  -> focused test / quality gate
```

## 6. 后续可扩展方向

后续可以通过 GitHub Design Proposal 评估：

- 接入 CI，作为 pull request 的只读架构边界门禁。
- 解析 Maven effective POM 或 dependency tree，覆盖传递依赖。
- 扫描 Java import/package，检查代码级越界引用。
- 为新增正式模块生成模块图更新提醒。

这些扩展都可能影响协作流程或构建门禁，应独立评估风险后推进。
