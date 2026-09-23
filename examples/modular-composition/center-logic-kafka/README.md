# Center / Logic Kafka 双进程验收切片

本目录包含三个独立 Maven 工件：

- `common-contract`：只包含 Center/Logic 共享 RPC 接口、DTO 和 codec。
- `center`：中心 provider JVM，拥有注册、心跳、摘除状态。
- `logic`：逻辑 caller JVM，使用独立 client、consumer group 和 reply topic。

## 构建

```powershell
mvn -f examples/modular-composition/center-logic-kafka/pom.xml clean install
```

## 外部 Kafka 运行

仓库提供 Windows/PowerShell 7 验收脚本。先完成上述三模块 `install`，再执行（`-Plan` 只显示计划，不产生运行通过证据）：

```powershell
pwsh -NoProfile -File scripts/VerifyCenterLogicKafka.ps1 -Plan
pwsh -NoProfile -File scripts/VerifyCenterLogicKafka.ps1
```

真实执行要求 Java 21、Docker Linux daemon 与 Compose；脚本创建隔离 broker 和两个 JVM，并在 `target/acceptance-evidence/center-logic-kafka/<runId>/` 写入 manifest 与日志。必须同时检查运行状态与清理状态。历史验收因外部前置条件不可用而 blocked，见[历史记录](../../../docs/migrations/20260915-kafka-and-production-audit.md)，不能把本地构建成功当作真实链路通过。

需要 Kafka 3.9.x（KRaft）可达地址。每次运行使用唯一 `topicPrefix`，例如 `zero.onboarding.<runId>`：

```powershell
java -cp <center-classpath> group.zn.zero.examples.centerlogic.kafka.center.CenterMain <bootstrap> <topicPrefix> <controlDir>
java -cp <logic-classpath> group.zn.zero.examples.centerlogic.kafka.logic.LogicMain <bootstrap> <topicPrefix> <controlDir>
```

Center 和 Logic 必须是两个独立 JVM。两端的 Kafka `clientId`、consumer group、reply topic 和 topic prefix 都带 run ID，不能使用默认固定 group。

## 生命周期与字段

```text
BROKER_READY -> CENTER_READY -> LOGIC_READY -> REGISTERED -> REQUESTED
-> DRAINING -> UNREGISTERED -> STOPPED
```

RPC envelope 由 `zero-rpc` 负责生成和传递 `correlationId`、`traceId`、`timeoutAt`；业务 DTO 只携带 `instanceId` 和业务状态。`logic.ready`、`logic.requested`、`logic.drain`、`logic.stopped` 以及 center markers 只用于验收控制，不是生产服务发现协议。

正常停止顺序是 Logic drain/unregister/close，再 Center close。重复 correlation、迟到响应、超时请求必须由 Kafka RPC pending 表和业务幂等策略显式处理；本示例不宣称 exactly-once。

## 边界

当前 `examples/modular-composition/center-logic` 仍是 direct/in-memory 单进程示例，不能作为本切片的分布式证据。Kafka 双进程切片也不代表生产就绪；认证、TLS、容量长稳、真实持久化、故障转移和 broker 集群恢复仍需独立验收。
