# 模块接入卡片

新补充 [七类可运行接入示例](../module-recipes/README.md)，为基础运行时、Actor/事件、发现、NPC、所有权、GM 和生产配置提供独立 main 与场景测试。其他模块沿用下表中的已有应用或契约入口。

这里为仓库中的 62 个 `zero-*` 模块和根示例目录提供接入卡片。每张模块卡片包含 Maven 坐标、真实源码与方法入口、验证命令和覆盖边界；没有独立应用示例的模块链接到可运行测试或现有组合根实现。

单个模块坐标不等于完整场景依赖。复制代码时同时参考对应 POM、imports、fixture 和资源关闭逻辑。外部测试可能因缺少服务而跳过，必须核对测试报告，不能仅凭 Maven 成功判断组件已验收。

模块接入顺序建议：先选择能力模块，再按需加入对应 runtime/Adapter；`zero-parent`、`zero-bom` 和 runtime provider 只负责构建或装配，不应被误当作业务 API。

| 模块 | 接入卡片 |
| --- | --- |
| zero-actor | [zero-actor](zero-actor.md) |
| zero-aoi | [zero-aoi](zero-aoi.md) |
| zero-benchmarks | [zero-benchmarks](zero-benchmarks.md) |
| zero-bom | [zero-bom](zero-bom.md) |
| zero-cache | [zero-cache](zero-cache.md) |
| zero-codegen | [zero-codegen](zero-codegen.md) |
| zero-core | [zero-core](zero-core.md) |
| zero-data | [zero-data](zero-data.md) |
| zero-data-mongo | [zero-data-mongo](zero-data-mongo.md) |
| zero-data-postgresql | [zero-data-postgresql](zero-data-postgresql.md) |
| zero-data-redis | [zero-data-redis](zero-data-redis.md) |
| zero-discovery | [zero-discovery](zero-discovery.md) |
| zero-discovery-nacos | [zero-discovery-nacos](zero-discovery-nacos.md) |
| zero-event | [zero-event](zero-event.md) |
| zero-frame-sync | [zero-frame-sync](zero-frame-sync.md) |
| zero-game | [zero-game](zero-game.md) |
| zero-gm | [zero-gm](zero-gm.md) |
| zero-gm-rest | [zero-gm-rest](zero-gm-rest.md) |
| zero-hot-update | [zero-hot-update](zero-hot-update.md) |
| zero-log | [zero-log](zero-log.md) |
| zero-logic | [zero-logic](zero-logic.md) |
| zero-monitor | [zero-monitor](zero-monitor.md) |
| zero-net | [zero-net](zero-net.md) |
| zero-net-kcp | [zero-net-kcp](zero-net-kcp.md) |
| zero-net-kcp-redis | [zero-net-kcp-redis](zero-net-kcp-redis.md) |
| zero-npc | [zero-npc](zero-npc.md) |
| zero-ownership | [zero-ownership](zero-ownership.md) |
| zero-ownership-postgresql | [zero-ownership-postgresql](zero-ownership-postgresql.md) |
| zero-parent | [zero-parent](zero-parent.md) |
| zero-player | [zero-player](zero-player.md) |
| zero-protocol | [zero-protocol](zero-protocol.md) |
| zero-ranking | [zero-ranking](zero-ranking.md) |
| zero-room | [zero-room](zero-room.md) |
| zero-rpc | [zero-rpc](zero-rpc.md) |
| zero-rpc-common | [zero-rpc-common](zero-rpc-common.md) |
| zero-rpc-discovery | [zero-rpc-discovery](zero-rpc-discovery.md) |
| zero-rpc-kafka | [zero-rpc-kafka](zero-rpc-kafka.md) |
| zero-runtime | [zero-runtime](zero-runtime.md) |
| zero-runtime-actor | [zero-runtime-actor](zero-runtime-actor.md) |
| zero-runtime-bootstrap | [zero-runtime-bootstrap](zero-runtime-bootstrap.md) |
| zero-runtime-cache | [zero-runtime-cache](zero-runtime-cache.md) |
| zero-runtime-data | [zero-runtime-data](zero-runtime-data.md) |
| zero-runtime-discovery | [zero-runtime-discovery](zero-runtime-discovery.md) |
| zero-runtime-event | [zero-runtime-event](zero-runtime-event.md) |
| zero-runtime-kafka | [zero-runtime-kafka](zero-runtime-kafka.md) |
| zero-runtime-kcp | [zero-runtime-kcp](zero-runtime-kcp.md) |
| zero-runtime-log | [zero-runtime-log](zero-runtime-log.md) |
| zero-runtime-mongo | [zero-runtime-mongo](zero-runtime-mongo.md) |
| zero-runtime-monitor | [zero-runtime-monitor](zero-runtime-monitor.md) |
| zero-runtime-nacos | [zero-runtime-nacos](zero-runtime-nacos.md) |
| zero-runtime-net | [zero-runtime-net](zero-runtime-net.md) |
| zero-runtime-postgresql | [zero-runtime-postgresql](zero-runtime-postgresql.md) |
| zero-runtime-production | [zero-runtime-production](zero-runtime-production.md) |
| zero-runtime-protocol | [zero-runtime-protocol](zero-runtime-protocol.md) |
| zero-runtime-redis | [zero-runtime-redis](zero-runtime-redis.md) |
| zero-runtime-rpc | [zero-runtime-rpc](zero-runtime-rpc.md) |
| zero-scene | [zero-scene](zero-scene.md) |
| zero-security | [zero-security](zero-security.md) |
| zero-server-starter | [zero-server-starter](zero-server-starter.md) |
| zero-server-starter-production | [zero-server-starter-production](zero-server-starter-production.md) |
| zero-state-sync | [zero-state-sync](zero-state-sync.md) |
| zero-world | [zero-world](zero-world.md) |
| 根示例目录 | [root-examples](root-examples.md) |
