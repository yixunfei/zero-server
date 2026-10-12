# Kafka 与 Nacos 本地集群

该目录提供本机隔离的 Docker Compose 环境：MySQL 8.4、双节点 Nacos 3.2.2 和三节点 Kafka 3.9.1。它用于 Adapter 集成验证；无须启动该环境即可运行本地/in-memory 示例。

PowerShell 7 在仓库根目录执行：

```powershell
$env:ZERO_NACOS_1_HTTP_PORT = '18848'
$env:ZERO_NACOS_1_GRPC_PORT = '19848'
$env:ZERO_NACOS_2_HTTP_PORT = '18849'
$env:ZERO_NACOS_2_GRPC_PORT = '19849'
$env:ZERO_KAFKA_1_PORT = '19092'
$env:ZERO_KAFKA_2_PORT = '19093'
$env:ZERO_KAFKA_3_PORT = '19094'
docker compose --project-directory examples/kafka-nacos-cluster -f examples/kafka-nacos-cluster/compose.yaml up -d --wait
```

Kafka bootstrap 地址为 `127.0.0.1:19092,127.0.0.1:19093,127.0.0.1:19094`；Nacos HTTP 地址为 `127.0.0.1:18848` 和 `127.0.0.1:18849`。完成隔离测试后执行：

```powershell
docker compose --project-directory examples/kafka-nacos-cluster -f examples/kafka-nacos-cluster/compose.yaml down --remove-orphans
```

配置只绑定 loopback，认证关闭且包含测试凭据，仅供本机临时验证，禁止直接用于共享或生产环境。若只需验证 Kafka 单节点，使用 [单 Kafka 示例](../kafka-nacos-external/README.md)；真实 center/logic RPC 流程见 [双进程 Kafka 场景](../modular-composition/center-logic-kafka/README.md)。
