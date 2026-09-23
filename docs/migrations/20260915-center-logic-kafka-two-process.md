# 20260915 center/logic Kafka 双进程迁移说明

## 1. 版本与责任信息

- 版本：`0.1.0-SNAPSHOT`，0.x 开发预览
- 范围：`examples/modular-composition/center-logic-kafka`
- 交付：common-contract、center、logic 与外部 Kafka 验收入口

## 2. 变更摘要

新增共享 RPC contract 及两个独立 JVM 的 center/provider 与 logic/caller 示例。Kafka settings 显式携带唯一 client ID、consumer group、topic prefix 和 reply topic；业务生命周期包含 register、heartbeat、drain、unregister 和 close。

## 3. 破坏性变更

无既有 core/runtime/protocol 公共 API 删除或协议 ID 变化。现有 `center-logic` 仍保持本地 direct/in-memory 语义。新示例要求显式 Kafka broker，不能使用默认固定 consumer group。

## 4. 迁移前准备

- 启动隔离 Kafka KRaft broker，并通过 API versions health probe 确认 ready。
- 为每次运行生成唯一 run ID；topic、client、group、reply topic 和 control directory 不得复用。
- 不在日志或 evidence 中写入凭据。

## 5. 迁移步骤

1. 构建本目录 reactor。
2. 先启动 center JVM，等待 `center.ready`。
3. 启动 logic JVM，等待 register/heartbeat/request marker。
4. 发送 drain，等待 in-flight 为零，再 unregister。
5. 关闭 logic，再关闭 center 和 broker；确认无残留进程、端口、容器和 volume。

## 6. 验证

本轮实际执行：

```text
mvnw.cmd -f examples/modular-composition/center-logic-kafka/pom.xml clean test
java scripts/ZeroAcceptanceEvidence.java --level quick --no-stage0 --maven mvn.cmd
```

三模块 clean test 通过。Docker daemon 探测失败，因此隔离 broker + 两独立 JVM 验收被准确记录为 `blocked`，证据位于 `target/acceptance-evidence/center-logic-kafka/manifest.json` 和 `target/acceptance-evidence/logs/center-logic-kafka.log`；不得把模块编译通过解释为 Kafka request/response 通过。

## 7. 回滚

删除新增示例目录或停止使用该 Maven module，不影响既有 `center-logic`。运行中止时先收集日志，再递归停止子进程并执行 compose down/volume cleanup。

## 8. 发布后观察

检查 correlationId、traceId、timeoutAt 是否贯穿 RPC envelope；检查重复/迟到响应没有完成新的 pending；检查 registration registry、drain 和资源关闭状态。该切片不提供 exactly-once、生产认证、TLS、容量或故障转移保证。

## 9. 完成确认

- [x] common-contract / center / logic 三项目结构。
- [x] provider/caller 独立 JVM 启动入口。
- [x] 显式 settings 与注册/摘除/drain marker。
- [ ] 隔离 Kafka broker 上的真实双 JVM request/response（当前环境 blocked）。
- [ ] late/duplicate 和 broker/container 无残留的真实 acceptance evidence。
- [x] productionReady 保持 false。

<!-- zero-migration-verification-and-rollback=required -->
