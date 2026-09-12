# 本地性能门禁

`zero-benchmarks` 提供一个短时、无外部依赖的 protocol frame codec 本地门禁。它用于检测明显回归，不是 JMH 替代品，也不代表生产容量、并发容量或 SLA。

## 运行（显式 opt-in）

在仓库根目录、JDK 21 下执行：

```powershell
mvn -Pperformance-gate -pl zero-benchmarks -am verify
```

门禁默认执行 3 轮预热、5 轮测量，每轮 10,000 次 decode，通常为短时本地检查。输出包含 `nsPerOp`、`opsPerSec`、payload 大小和校验值；失败时返回非零退出码，并明确打印实际值与阈值。

可通过 Maven 参数调整保守阈值或样本量（仅建议在稳定的本地环境使用）：

```powershell
mvn -Pperformance-gate -pl zero-benchmarks -am verify `
  -Dperformance.gate.args="--warmups=3 --measurements=5 --operations=10000 --max-ns=100000 --min-throughput=5000"
```

## 解释与边界

- 该 runner 测量现有 `ZeroBinaryFrameCodec` 的固定小 payload 编解码路径，不修改业务 API 或协议格式。
- JVM、操作系统、CPU、后台负载和 GC 会影响结果；应将结果作为同机趋势信号，而非跨机器排名。
- 阈值默认刻意保守，仅用于捕获数量级回归；不要据此宣称生产容量。
- 该 profile 默认关闭，普通 Maven 构建不会执行门禁。
- 这不是长时间压测，不连接网络、数据库、Kafka、Docker 或外部服务。
- 若机器较慢，可显式放宽阈值；若要建立正式统计证据，应另行使用 JMH 并记录环境元数据。

`--help` 可查看 runner 参数：

```powershell
java -cp zero-benchmarks/target/classes;zero-protocol/target/classes group.zn.zero.benchmark.ProtocolCodecGate --help
```
