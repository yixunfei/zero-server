# 20260915 平台与生产专项验收门禁

## 变更

新增 `scripts/ZeroPlatformProductionGate.java`：

- `--platform-transaction`：运行脚手架事务 focused tests，记录 OS、Java、runId、退出码和新鲜日志。
- `--local-production-focused`：运行 production network、GM、release artifact 和 protocol codec performance gate；这些是本地 focused/回归证据，不是生产容量或身份系统证明。
- `--external-production`：仅在 Docker/Kafka/外部服务前置具备时运行真实外部验证。

新增 CI `platform-transaction` job，使用 Ubuntu/macOS/Windows matrix，上传 `target/production-gate`，并为 Windows 先创建 `target/cross-platform`。

## 当前验证

Windows Java 21 fresh run：

```text
--platform-transaction = passed
--local-production-focused = passed
```

`ZeroAcceptanceEvidence` 已接入 `matrix.platform-transaction` 和 `production.local-focused`。Linux/macOS 结果必须由对应 hosted runner 重新执行，不能用 Windows 结果替代。

真实 Kafka verifier 仍因 Docker Linux daemon 不可用而为 `blocked`，不伪造双 JVM request/response 或 drain 证据。

## 回滚与边界

删除 gate 脚本、CI job 和相关 evidence records 即可回滚。生产专项仍需真实中间件、容量阈值、证书/身份、GM 审计 sink、RPO/RTO 和受信任发布身份；`productionReady=false`、`goalAchieved=false` 保持有效。

## 可靠性修复

恢复 CI 独立顶层 `performance` job，platform transaction job 在 POSIX 使用 Maven Wrapper、Windows 预创建证据目录；gate 可通过 `ZERO_MAVEN_CMD` 指定构建器，并要求四个事务测试的明确摘要，避免裸 `Tests run:` 或 0 tests 被误判。

## GitHub Actions 首次真实矩阵运行

Gate commit `7bb28757c2c622b50bf76b4c7c2dc09aa9f08f8c` 已推送，CI run `34981381210` 实际启动 Ubuntu/macOS/Windows platform transaction jobs。三项 job 均执行 checkout、Java 21、gate 和 evidence upload，但 gate step 均为 failure；当前凭据无法下载私有 job logs/artifacts（API 返回 403 admin required），因此没有把 artifact 解释为 passed。下一次修复应先取得 job log，定位 wrapper/测试摘要失败原因，再重跑矩阵。
