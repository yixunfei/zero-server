# World / Shard 本地最小示例

`examples/world-shard` 演示正式 `zero-world` API 的单 JVM、内存 world/shard 实体归属与单实体迁移：enter、owner move、source lock、target prepare/commit、source release、route epoch fencing 和 migrationId 重放。

```text
mvn -B -ntp -f examples/world-shard/pom.xml clean test
mvn -B -ntp -f examples/world-shard/pom.xml exec:java
```

示例始终输出 `productionReady=false`。不包含跨进程 handoff、真实 RPC/discovery、持久化、AOI stitching、跨服广播、批量迁移或生产容量承诺。
