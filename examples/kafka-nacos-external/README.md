# 单 Kafka 外部测试环境

该目录提供一个本机 Kafka 3.9.1 broker，供需要真实 Kafka transport 的隔离集成测试使用。默认 local 示例不需要 Docker 或 Kafka。

PowerShell 7 在仓库根目录启动：

```powershell
$env:ZERO_KAFKA_PORT = '19092'
docker compose --project-directory examples/kafka-nacos-external -f examples/kafka-nacos-external/compose-kafka-only.yaml up -d --wait
```

应用连接 `127.0.0.1:19092`。测试结束后关闭：

```powershell
docker compose --project-directory examples/kafka-nacos-external -f examples/kafka-nacos-external/compose-kafka-only.yaml down --remove-orphans
```

端口只绑定 loopback，broker 使用明文且不启用鉴权，不得暴露到共享网络。双进程 RPC 完整流程见 [center/logic Kafka 示例](../modular-composition/center-logic-kafka/README.md)。
