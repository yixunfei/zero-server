# Git 与贡献工作流

本文说明 zeroServer 的公开 Issue、分支、提交、Pull Request、验证与发布协作方式。完整行为规范见根目录的 `CONTRIBUTING.md`。

## 1. Issue 与设计评审

可以直接提交 Bug Report、Feature Request 或 Documentation Issue。以下变化应先提交 Design Proposal，避免实现完成后才发现公共边界不可接受：

- 公共 API / SPI、注解、协议 DSL 或代码生成规则。
- 协议 ID、wire format、RPC 语义和兼容策略。
- Actor / 线程模型、执行域、线程池或背压。
- 存储格式、对象映射、缓存 key、持久化和迁移。
- GM、权限、审批、审计、安全日志或 ErrorCode。
- 热更、ClassLoader、跨节点同步。
- 新增正式模块、模块大重构或依赖方向变化。

Design Proposal 至少包含现状、目标、非目标、接口草图、依赖方向、线程安全、兼容与迁移、性能、安全、可观测性、测试和回滚方案。

## 2. 分支

从最新 `main` 创建短生命周期主题分支：

```bash
git switch main
git pull --ff-only
git switch -c feat/short-description
```

推荐前缀：`feat/`、`fix/`、`docs/`、`refactor/`、`perf/`、`test/`、`build/`、`ci/`。大型跨模块改动应拆成可独立评审的提交或 Pull Request，但不要为了文件长度机械拆分同一原子语义。

## 3. 提交信息

推荐使用清晰的类型前缀：

```text
feat: add ...
fix: prevent ...
docs: explain ...
refactor: extract ...
perf: reduce ...
test: cover ...
build: update ...
ci: verify ...
chore: maintain ...
```

提交信息必须描述实际影响；不要把生成物、`target/`、日志、IDE 设置、本地凭据或无关文件带入提交。提交应使用贡献者可公开的姓名和邮箱。

## 4. Pull Request

Pull Request 应：

- 说明问题、方案、范围和非目标。
- 链接相关 Issue / Design Proposal。
- 列出影响模块、公共契约、兼容性和迁移。
- 说明线程安全、性能、资源关闭、安全与可观测性影响。
- 提供自动测试、手工验证和未验证项。
- 同步 `CHANGELOG.md` 与受影响文档。
- 模块、包、入口或依赖方向变化时同步 `docs/module-map.md`。

尽量保持 PR 聚焦。修复与无关格式化、依赖升级或大规模重命名应分开，便于定位风险和回滚。

## 5. 本地验证

Java 21 与 Maven 3.9+ 环境下，基础检查为：

```bash
java scripts/ZeroLocalDoctor.java
java scripts/ZeroArchitectureGuard.java
mvn -B -ntp test
mvn -B -ntp -Pquality verify
```

涉及本地集成链路时追加：

```bash
mvn -B -ntp -Pintegration-tests verify
```

涉及示例或模板时，按变更范围运行：

```bash
mvn -B -ntp -DskipTests install
mvn -B -ntp -f examples/<example>/pom.xml test exec:java
java scripts/VerifyLocalScaffolds.java --outputDir target/scaffold-verify
```

涉及 Kafka、MongoDB、Redis、PostgreSQL 或 Nacos 时，在隔离且已授权的环境中显式运行：

```bash
mvn -B -ntp -Pexternal-tests verify
```

Pull Request 必须写明未运行项、原因和剩余风险。外部测试记录组件与版本、非秘密配置来源、测试范围和清理方式；不得提交真实密码、token 或连接串。

## 6. Changelog 与版本

项目维护 `CHANGELOG.md`。用户可见能力、行为变化、修复、性能变化、弃用、破坏性变化和迁移要求都应记录在 `Unreleased`。

版本号由维护者决定。`0.x` 阶段可能出现破坏性变化，但仍必须提供清晰的 Changelog、迁移说明和回滚边界。正式发布前使用 `docs/release-checklist.zh-CN.md` 做分层复核。

## 7. 合并与历史

- 必需 CI 检查通过后再合并。
- 默认分支保持可构建；不要通过跳过测试掩盖失败。
- 维护者根据变更规模选择 squash、rebase 或 merge；Pull Request 内提交应保持可理解。
- 禁止 force-push 到受保护的 `main`。
- 发现凭据进入历史时，立即撤销/轮换凭据并按 `SECURITY.md` 私下报告；仅删除最新文件不足以消除历史泄漏。

<!-- zero-git-verification=required -->
