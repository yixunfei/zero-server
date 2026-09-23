# 2026-09-23 公开检出 CI 入口修复

适用 `0.1.0-SNAPSHOT`。不改变业务 API、协议或数据格式，无业务迁移步骤。

## 变更

推送后的 CI 在公开检出中发现两类环境依赖：能力台账及发布材料检查引用未提交的维护者脚本、
`.codex/`、`tasks/` 和旧源码注释；平台事务测试只构建 zero-codegen，缺少其 SNAPSHOT 依赖。

- `ZeroFrameworkGapLedger` 的证据改为已跟踪的指南、迁移、契约、源码、测试和性能报告；保留能力状态、
  生产边界与必需材料缺失即失败的规则。RPC/Repository 仍只有 readiness，不用本地脚本存在冒充实测。
- `ZeroReleaseHardeningReadiness` 严格检查公开发布规范和 `ZeroUnifiedEntryVerifier`，不依赖维护者本机文件。
- 平台事务入口先构建并安装 zero-codegen 的 reactor 依赖，再独立执行原有三类指定测试；
  测试阶段不关闭“指定测试不存在则失败”的保护。两步退出码都进入 manifest。
- Windows 子进程保留实际 `Path` 环境键，避免因新增 `PATH` 丢失 Maven 和系统工具目录。

## 验证与边界

只复制 Git 跟踪文件到独立目录后，能力台账与发布材料严格检查通过；本机平台事务入口通过。
推送前依赖升级候选的完整 `quality,benchmarks,integration-tests` reactor 已通过。
相关日志保存在维护者本机 `target/remote-main-cleanup-20260923`，远端 CI 结果按对应提交单独核对。

这些修复不授权发布，也不证明外部中间件、Linux 原生传输或生产容量已通过。
需要回滚时可回退该 CI 修复提交，但旧入口会再次依赖本机私有材料与已安装 SNAPSHOT，不能视为公开检出验收通过。
