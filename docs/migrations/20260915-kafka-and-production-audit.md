# 20260915 真实 Kafka 双 JVM 验证与剩余专项审计

## 真实运行结果

在当前 Windows 环境使用 Java 21 与 PowerShell 7 runtime wrapper 运行：

```text
pwsh -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass \
  -File scripts/VerifyCenterLogicKafka.ps1
```

本机 Docker CLI/Compose 可用，但 Docker Linux daemon 不可用（Docker Desktop Linux engine named pipe 不存在），因此 verifier 如实生成新鲜 blocked evidence：

```text
center-logic-kafka=blocked|runId=b0083d8eff1e467aa4608df91360d08e|...
exit=2
reason=Docker daemon unavailable
cleanupStatus=failed
```

`ZeroAcceptanceEvidence` 将该结果分类为 `matrix.center-logic=blocked`，不是代码 `failed`；blocked 仍导致总证据 `incomplete` 和非零退出。没有使用 `-Plan` 或历史 passed manifest 代替真实双 JVM 运行。

## 可运行本地专项

Java 21 下 production network focused Maven tests（zero-net、zero-runtime-net、zero-server-starter-production）通过，证明本地契约/生命周期切片；`ZeroProductionNetworkFocusedTestPlan` 的旧文档路径已迁移修正，但其缺失历史 marker 仍保持 failed/not-proven，未将静态计划当作生产证据。

## 未覆盖项

真实 Kafka 双 JVM 的 request/response、注册摘除、超时/迟到、排空、停止和资源回收必须在 Docker daemon、Kafka image、PowerShell 7、Java 21 均可用的环境重新执行并审查逐项 manifest。Linux/macOS runner、容量/长稳、真实 TLS/认证、GM 持久审计、RPO/RTO/PITR 和受信任发布回滚仍需独立环境与项目方输入。

## 20260915 fresh retry

在当前环境再次使用 Java 21 与 PowerShell 7 runtime wrapper 执行真实 verifier，Docker context `desktop-linux` 仍无法连接 Linux daemon。新鲜 runId 为 `8821fb288b3c4a62905c413f32ef090f`，manifest 状态为 `blocked`，原因 `Docker daemon unavailable`，退出码为 2。Kafka image 未能启动，因此没有 center/logic JVM、request/response、注册摘除、超时/迟到、排空、停止或资源回收的通过证据。

## 20260915 Docker 恢复后的真实运行

Docker Linux daemon 恢复后，使用 PowerShell 7 与 Java 21 运行真实 verifier，runId `1904306ad1c74b8ab85f992f31526b16`，manifest `status=passed`、broker `healthy`、center/logic PID 与 exit code 均成功、cleanup `0`。control 目录包含 `center.ready`、`logic.ready`、`logic.requested`、`logic.drain`、`center.drained`、`logic.stopped`、`center.stopped`；随后 acceptance evidence 的 `matrix.center-logic` 也为 `passed`。

审查边界：本次 verifier 的 manifest assertion 列表和 control markers 证明该 fixture 的独立 JVM 生命周期、注册/摘除、请求路径、排空/停止和资源回收；`logic.requested` 记录的是 fixture 的 trace/timeout marker，并不替代生产级 Kafka 延迟/重复注入或容量/长稳证据。
