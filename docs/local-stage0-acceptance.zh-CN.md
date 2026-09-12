# 阶段 0 开箱即用验收

本文说明 `scripts/ZeroStage0Acceptance.java` 的职责、命令、验收层级和安全边界。它把分散的环境诊断、架构守卫、Maven 门禁、独立示例和七类脚手架验证组合为一个确定性入口，供新用户、贡献者和 CI 使用。

## 1. 验收目标

阶段 0 回答以下问题：

- 当前终端是否真的使用 Java 21、Maven 3.9+ 和可用的 Git。
- 核心模块依赖方向是否仍满足公开架构约束。
- 默认测试、质量门禁和本地集成测试是否通过。
- 无 Docker 的本地 Starter 是否能够形成完整业务闭环。
- 独立示例是否能够测试并运行。
- 用户能否从需求关键词生成项目、生成协议代码、运行测试并找到第一个业务修改位置。
- 七类本地脚手架能否全部生成、检查、测试和运行。

阶段 0 不回答生产容量、p99、SLA、长稳、真实中间件灾备或公网安全问题。

## 2. 两个验收层级

| 层级 | 用途 | 检查内容 |
| --- | --- | --- |
| `quick` | 克隆后首次上手、日常关键路径 smoke | Doctor、架构守卫、框架边界守卫、默认测试、安装当前 SNAPSHOT、需求驱动本地原型 |
| `full` | 提交前、阶段验收和 CI | `quick` 全部内容，加质量门禁、本地集成测试、四种精简消费者、本地 Starter、六类独立示例、七类业务脚手架和十九种按需生成工程 |

默认层级是 `quick`。快速验收：

```bash
java scripts/ZeroStage0Acceptance.java --level quick
```

完整验收：

```bash
java scripts/ZeroStage0Acceptance.java --level full
```

只查看将执行的确定性命令，不创建目录、不运行 Maven：

```bash
java scripts/ZeroStage0Acceptance.java --level full --plan
```

## 3. 完整验收顺序

`full` 按固定顺序串行执行：

```text
ZeroLocalDoctor
  -> ZeroArchitectureGuard
  -> ZeroFrameworkBoundaryGuard
  -> Maven test
  -> quality verify
  -> integration-tests verify
  -> install 当前 SNAPSHOT
  -> 四种按需装配独立消费者（modular-consumers）
  -> 本地 Starter 完整 Demo
  -> CSV 热重载示例
  -> 受管 Scheduler 示例
  -> 可观测性安全门示例
  -> RPG 本地业务示例
  -> TCP generated dispatcher 示例
  -> Repository 业务替换示例（本地读写）
  -> 需求关键词本地原型
  -> 七类脚手架批量验证
  -> 十九种按需生成工程、实际依赖闭包、诊断与自定义实现检查
```

检查在首个失败后停止，避免缺少前置制品时继续制造级联错误。每项检查默认最多运行 600 秒，可在 30～3600 秒范围内调整：

```bash
java scripts/ZeroStage0Acceptance.java \
  --level full \
  --timeoutSeconds 900
```

`integration-tests verify` 必须真实运行本地跨模块闭环并输出 `zero-local-integration=ok`；只有 Maven 退出码为 0 但 Failsafe 没有执行该闭环时，`full` 仍判定失败。`*ExternalIT` 始终被该 profile 排除。

## 4. 输出与机器摘要

默认输出目录：

```text
target/stage0-acceptance/
  logs/
  prototype/
  scaffolds/
```

每个子命令的完整输出写入独立 UTF-8 日志。成功摘要示例：

按需生成验收还在 `target/generated-composition-verify/` 保存生成工程、Maven 构建日志与 `target/runtime-classpath.txt`。单 Redis 只装配与关闭，默认不执行外部服务启动或数据读写。

```text
zero-stage0-acceptance=ok|level=quick|checks=5|passed=5|failed=0|skipped=0|durationMs=...|externalMiddleware=false|productionReady=false|outputDir=target/stage0-acceptance
```

失败时摘要使用 `failed`，并包含已通过、失败和因 fail-fast 未执行的检查数。单项输出给出稳定检查 ID、耗时、退出码/标记状态和日志位置，不把完整日志压缩进摘要。

CI 应匹配最终 `zero-stage0-acceptance=ok` 和进程退出码，不应只搜索某个示例的 `=ok`。

CI 可选择直接运行同一个 `full` 入口；默认 CI 也可以拆分执行同等的 unit、quality、integration 和 architecture 门禁，但文档与 workflow 必须保持一致。若运行 Stage 0，GitHub Actions 应保留最终机器摘要；无论验收成功或失败，都应将 `target/stage0-acceptance/logs` 作为 `stage0-acceptance-logs` 制品上传并保留 7 天，便于按稳定检查 ID 定位问题。

当前 Reactor 与检查数量会随模块、测试和工作树版本变化；历史摘要仅作为历史记录，不是当前 checkout 的验收证据。提交或发布时必须在 JDK 21 环境重新执行并保存本轮输出。真实驱动验证不并入本地 full，不包含服务端故障恢复或压测。

## 5. 源码长度质量门禁

`full` 中的 `quality verify` 通过 Checkstyle 执行以下硬约束：

- Java 单个源码文件最多 1500 行。
- Java 单个方法或构造方法最多 100 行。

按需装配将 Adapter provider 移至各自集成模块，`ZeroProductionRuntimeBuilder` 只保留全量组合入口，共享配置和生命周期交给 `ProductionAssembly`。长度门禁用于暴露职责混杂，不要求为了数字机械拆分类或方法。

## 6. 文件和进程安全

- `--outputDir` 必须是当前仓库 `target/` 的子目录，不能指向仓库根目录、`target/` 本身或外部目录。
- 工具不会递归删除用户目录；生成器的 `--force` 只覆盖已知脚手架文件。
- 子进程按顺序执行，不并发写 Maven Reactor 的同一 `target/`。
- 单项超时后只终止由当前检查启动的进程及其后代。
- 工具不读取或打印真实中间件凭据，不连接 Kafka、Nacos、Redis、MongoDB 或 PostgreSQL。
- 所有模板继续声明 `externalMiddleware=false` 和 `productionReady=false`。

## 7. 失败排查

### Doctor 失败

先比较：

```bash
java -version
mvn -version
```

Maven 的 `Java home` 必须指向 Java 21。Windows 多 JDK 环境可参考[快速上手](quickstart.zh-CN.md)切换当前 PowerShell 会话。

### Maven 门禁失败

进入摘要给出的日志文件，按失败检查 ID 单独复跑对应命令。不要用后续脚手架成功替代单元测试、质量或集成门禁。

### 示例或脚手架失败

完整验收会先安装当前 SNAPSHOT。若单独执行示例或 `VerifyLocalScaffolds`，需先运行：

```bash
mvn -B -ntp -DskipTests install
```

生成项目的进一步诊断入口为 `InspectLocalScaffold` 和 `RunLocalScaffold`。

## 8. 与生产验证的边界

阶段 0 只证明本地开发闭环在当前环境可复现。进入真实项目上线前仍需单独执行：

- `external-tests` 和隔离中间件环境验证。
- TLS、鉴权、GM 权限、审批和审计链路验证。
- 生产日志落地、Prometheus Endpoint 和告警通道验证。
- 负载、背压、故障注入、恢复、容量和长稳测试。
- 协议、数据、缓存、配置和部署迁移/回滚演练。

完整发布分层见[发布检查单](release-checklist.zh-CN.md)，当前能力边界见[能力矩阵](capability-matrix.zh-CN.md)。

## 9. 最近复验

2026-09-08，在 PostgreSQL 连接池及资源关闭回归修正后重新执行 full：17/17 通过，耗时 492191 ms，失败和跳过均为 0。Reactor Surefire 531 项、本地 Failsafe 1 项全部通过；质量门禁、47 模块 / 18 规则架构守卫以及十九种生成消费者均通过。未选择 PostgreSQL 的生成消费者额外验证 HikariCP 类缺席。

真实 Repository 的停启、并发与持续运行通过独立 `scripts/VerifyRepositoryDrivers.ps1 -Resilience` 入口验证，不计入本地 full。具体记录与下一阶段顺序见[按需组装推进方案](demand-driven-composition.zh-CN.md)。
