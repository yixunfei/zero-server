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

## 第二次 CI 矩阵运行

提交 `b44b2972e4f1aac8198f1a9f903d11ed1ab36419` 修复平台 gate marker 后，CI run `34995588781` 的 Ubuntu/macOS/Windows platform transaction jobs 均为 `success`。artifact 列表存在且上传成功，但当前 API 凭据下载 artifact 返回 401，无法读取 manifest 内容；job conclusion 只能证明 runner gate 命令成功，不能替代 manifest 逐字段审查。

## Follow-up diagnosis

CI run `34995588781` 的三个 platform jobs 均 success；后续本地审查发现 gate 若要求精确测试摘要会受 Surefire 实际计数影响，因此工作树中的 `0d33f72` 将 marker 调整为当前提交中三个选择类实际合计的 `Tests run: 10`。该修复尚未推送（当前网络连接 GitHub 失败），所以不应将其视为 runner 证据。artifact 下载仍需 Actions 读取权限。

## 第三次 CI 矩阵运行

提交 `2b216370b8f6dbb1500b6de8eb23e7f0a5c8e7c8` 修正 gate marker 后，CI run `35001808595` 的 Ubuntu/macOS/Windows platform transaction jobs 均为 `success`，对应 artifacts 已上传（Ubuntu `10410096994`、macOS `10410520340`）。当前会话的 artifact API 读取仍返回 401，故无法读取 manifest 内容；job conclusion 证明 gate 命令完成成功，但不替代逐字段 artifact 审查。
