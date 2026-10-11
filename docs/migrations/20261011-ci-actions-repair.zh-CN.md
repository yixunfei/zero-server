# GitHub Actions 验收修复（2026-10-11）

## 适用范围与迁移

本次修复针对 main 提交 `e7eddbf` 的 CI 运行 `38060200624`。不新增框架 API，不改变协议、存储格式或运行时执行语义；现有应用无需数据迁移。

- 依赖审核清单同步已合并的 Netty `4.2.18.Final`、Jedis `8.0.1`。依赖硬边界、循环检测和负例夹具继续执行。
- Maven Wrapper 固定版本仍由 `.mvn/wrapper/maven-wrapper.properties` 管理；验证器检查 Maven Central 的 `3.9.x` URL、目录与压缩包版本一致，不再重复固定 `3.9.8`。
- `center-logic-kafka` 示例的 `zero.version` 改为当前 `0.1.2-SNAPSHOT`，使用本地安装的对应 BOM。事件/Actor 示例增加当前事件总线所需的受管执行器依赖断言。
- CLI 退出码矩阵通过当前 reactor 的 `zero-codegen-*-all.jar` 启动正式 CLI，保留成功、生成失败、参数错误、计划冲突的 `0/1/2/3` 退出码与错误标记检查。
- macOS 平台事务作业通过 `JAVA_TOOL_OPTIONS` 将 Java 临时目录设为 runner 临时目录的物理路径，避开 `/var -> /private/var` 系统别名。脚手架拒绝符号链接祖先的安全策略继续生效。
- 完整 Stage 0 后续检查发现配置热重载示例遗漏现有授权接口装配。示例在启动前仅授权本进程 `example-local` 操作者对 `items` 表的手动请求，不授权 watcher；真实应用应接入可信身份与权限端口，不能把请求中的 operator 字段当作身份认证。框架默认拒绝策略和公共 API 不变。

## 验证证据

原 CI 中单元测试、质量、集成、API 门禁及 Ubuntu/Windows 平台事务通过。失败日志及验收 artifact 确认上述六类问题。

本地使用 Java 21 验证完整构建、架构门禁、依赖守卫的 21 个正负夹具、两个受影响示例、CLI `0/1/2/3` 退出码及统一入口静态检查。完整 Stage 0 默认单元测试亦通过。

本机 Windows PowerShell 在加载统一入口脚本时发生 AMSI 原生崩溃，完整入口的 Windows/Linux/macOS 结果以该修复提交对应的 GitHub CI 三平台作业为准；不能用 Windows 本地静态检查代替真实平台运行。外部中间件、正式发布和手动性能作业不在此次默认 CI 修复范围内。

复核命令：

```bash
java scripts/ZeroArchitectureGuard.java
java scripts/VerifyDependencyGuard.java
java scripts/ZeroPlatformProductionGate.java --platform-transaction
java scripts/ZeroUnifiedEntryVerifier.java --full-smoke
java scripts/ZeroAcceptanceEvidence.java --level full --local-only --no-stage0
java scripts/ZeroStage0Acceptance.java --level full
```

## 回滚边界

可以整体撤销本次 CI 修复提交；撤销后过时断言、旧 BOM、缺失类路径和 macOS 临时路径问题会重新出现。没有运行时数据回滚步骤，本次不代表正式发布或外部中间件验收。
