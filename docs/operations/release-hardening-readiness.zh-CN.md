# 发布硬化 Readiness

## 1. 定位

本页把 zeroServer 当前已有的开源治理、CI、Maven 验证层级、发布检查单和迁移模板收拢成一条只读证据链。它服务于贡献者和维护者判断“是否已经具备继续准备发布的材料”，不自动执行测试、签名、发布或部署。

当前状态：`partial-evidence`。

<!-- CI layers: unit / quality / integration / architecture; external middleware and performance are workflow_dispatch opt-in only. -->

```text
治理文件 + Issue 模板 + CI / Maven 分层 + build smoke
  -> 发布检查单
  -> 迁移说明模板
  -> ZeroReleaseHardeningReadiness
  -> 后续经确认的质量阈值 / 签名 / 制品发布 / 部署与回滚演练
```

`partial-evidence` 表示基础材料和检查入口已经存在，但正式 release gate、覆盖率阈值、外部依赖矩阵、签名、制品仓库、兼容窗口与生产回滚演练尚未冻结。

## 2. 当前证据

| 证据 | 当前状态 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| `CHANGELOG.md` / `Unreleased` | 已有 | 重要变更有统一记录入口 | 当前内容已完成版本归类或迁移审阅 |
| `CONTRIBUTING.md` | 已有 | 贡献与质量要求可发现 | 所有贡献都已经过线上 gate |
| `SECURITY.md` / `CODE_OF_CONDUCT.md` / `LICENSE` | 已有 | 安全报告、社区行为与 MIT 许可入口齐备 | 私有漏洞通道、SLA 或法律审阅已完成 |
| `.github/ISSUE_TEMPLATE/` | 已有 | bug、feature、design 有结构化输入 | Issue 流程能替代高风险用户确认 |
| `.github/workflows/ci.yml` | 已有 | Java 21、validate、test、quality、示例与脚手架命令已声明 | GitHub Actions 当前提交已经在线通过 |
| `zero-parent/pom.xml` | 已有 | Java 21 与 quality / integration / external profile 已声明 | 阈值、真实中间件矩阵或 release profile 已冻结 |
| `ZeroBuildSmokeVerifier` | 维护者本地材料，未随公开仓库发布 | 仅作历史材料检查；公开检出可直接执行 `mvn -B -ntp -DskipTests validate` | 单元测试、质量门禁、性能或生产验收通过 |
| [发布检查单](release-checklist.zh-CN.md) | 已有 | 发布前检查维度被统一列出 | 检查项已经由维护者逐项签字 |
| [迁移说明模板](../migrations/template.zh-CN.md) | 已有 | 破坏性变更有最低记录结构 | 某个具体版本已经完成迁移说明 |

## 3. 七层验证与动作边界

| 层级 | 典型入口 | 当前自动程度 | 本 readiness 是否执行 | 说明 |
|---|---|---:|---:|---|
| `validate` | `mvn -DskipTests validate` / `ZeroBuildSmokeVerifier` | CI + 可选本地 | 否 | 只验证 Maven 模型和基础插件阶段 |
| `default-tests` | `mvn test` | CI | 否 | 无真实外部中间件的默认测试 |
| `quality` | `mvn -Pquality verify` | CI | 否 | Checkstyle、PMD、SpotBugs、JaCoCo 报告；当前不宣称覆盖率阈值已冻结 |
| `integration-tests` | `mvn -Pintegration-tests verify` | 按改动选择 | 否 | 本地集成测试，需在任务 `VERIFY.md` 记录 |
| `external-tests` | `mvn -Pexternal-tests verify` | 显式 opt-in | 否 | 需要真实 Kafka / MongoDB / Redis / PostgreSQL / Nacos 环境与单独建档 |
| `performance` | JMH、压测、长稳、容量验证 | 尚未形成正式门禁 | 否 | 七条 track 目前仍只有 readiness / partial evidence |
| `release-actions` | version、tag、sign、publish、deploy | 未自动授权 | 否 | 会改变外部状态，必须由维护者确认并提供凭据与目标环境 |

上表刻意区分“仓库中存在命令”和“命令已成功执行”。只读审计只检查路径与稳定 marker，不把文本存在当作执行结果。

## 4. 只读验证器

从仓库根目录使用 Java 21 运行：

```powershell
java scripts/ZeroReleaseHardeningReadiness.java --allow-missing-evidence
java scripts/ZeroReleaseHardeningReadiness.java --listChecks
java scripts/ZeroReleaseHardeningReadiness.java --help
```

完整维护者环境中的摘要示意（计数以实际输出为准）：

```text
zero-release-hardening-readiness=ok|paths=19/19|markers=32/32|levels=7|requiresConfirmation=true|warnings=0
```

检查内容包括：

- 开源治理文件、三类 Issue 模板、CI workflow 和 Maven 配置入口存在。
- CI 声明 Java 21、`validate`、默认测试和 `quality`。
- Maven 声明 Java 21、`quality`、`integration-tests` 与 `external-tests`。
- Changelog 存在 `Unreleased`，发布检查单和迁移模板具备最低章节。
- readiness 明确 `partial-evidence`、`requiresConfirmation=true` 与七层边界。

公开检出不包含部分维护者脚本与本机协作规则，因此使用 `--allow-missing-evidence` 并检查逐项 `[FAIL]`、`paths` 和 `markers` 的实际计数。当前宽松模式即使存在缺项，摘要仍可能输出 `ok` 和 `warnings=0`，不能据此判断材料完整或 CI 通过。严格模式仅适用于材料齐全的维护者环境。验证器不会启动 Maven 或子进程，不会访问网络，也不会修改仓库。

- 本地 release artifact rehearsal 已可生成源码包、SHA-256、CycloneDX 结构化 SBOM、临时 Ed25519 签名、篡改拒绝和 v1/v2/v1 回滚证据；这不等于受信任身份签名、制品仓库发布或生产回滚演练。

准备某个版本时建议按以下顺序工作：

1. 为版本建立独立任务档案，列出目标、范围、风险和验证计划。
2. 从 [发布检查单](release-checklist.zh-CN.md) 复制一份版本实例，填写版本、负责人和证据链接。
3. 若有破坏性变更，从 [迁移说明模板](../migrations/template.zh-CN.md) 创建具体迁移说明，不直接改写模板为某个版本的结果。
4. 运行只读 readiness，确认基础材料没有缺失。
5. 按变更范围执行 validate、默认测试、quality、integration / external tests 和性能验证，并把未执行项写入 `VERIFY.md`。
6. 对版本、签名、tag、制品仓库、release gate、部署和回滚演练单独取得确认。
7. 发布后记录制品校验、观察结果、已知问题和回滚窗口，再归档任务。

## 6. 高风险边界

以下动作不属于本 readiness，且不能因为本页检查通过而自动执行：

- 修改质量、覆盖率、JMH、延迟、吞吐或容量阈值。
- 把新的检查升级为 CI 阻断条件或改变分支保护策略。
- 冻结公共 API / SPI、协议、存储、缓存、日志字段或长期兼容窗口。
- 修改版本、移除 `SNAPSHOT`、创建 tag / release / 分支 / commit / PR。
- 配置签名密钥、token、仓库凭据或上传 Maven 制品。
- 运行 Docker、真实中间件、external-tests、JMH、压测、生产部署或数据清理。
- 执行生产回滚、迁移或热更。

上述动作必须按 `AGENTS.md` 建档、评估影响，并在需要时等待用户或维护者确认。`requiresConfirmation=true`。

## 7. 当前剩余缺口

- 没有正式 release profile、制品签名、仓库发布和校验流程。
- 没有冻结 JaCoCo 覆盖率、质量告警或性能回归阈值。
- 没有完整的外部中间件版本矩阵和定期 online CI 证据。
- 没有明确的长期兼容窗口、弃用周期和支持版本策略。
- 没有正式发布、升级、回滚、灾备和长稳演练记录。
- 正式生产运行时和玩法组件仍受高风险确认门禁约束。

因此，本 readiness 通过后，release-hardening 仍只能标为 `partial`，zeroServer 仍不能宣称 production ready。
