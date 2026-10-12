# 可运行示例

新增 [七类模块接入与常见场景](module-recipes/README.md)：基础运行时、事件/Actor、发现、正式 NPC、所有权、GM/REST、生产配置诊断，均含 main、中文注释和回归测试。

新增[显式脚手架 catalog 最小示例](scaffold-catalog/README.md)：无需第三方依赖即可生成、装配并测试自定义 Provider。

先在仓库根目录用 JDK 21、Maven 3.9+ 执行 `mvn -B -ntp -DskipTests install`。以下命令中的 POM 均相对于仓库根目录；本地 smoke 运行后退出，Kafka 双 JVM 示例按各自生命周期显式停止。

| 场景 | 说明 | 命令 |
| --- | --- | --- |
| 模块接入常见场景 | [七个独立应用：运行时、Actor、发现、NPC、所有权、GM、生产诊断](module-recipes/README.md) | `mvn -B -ntp -f examples/module-recipes/pom.xml test`；各目录单独运行 `exec:java` |
| RPG 登录与移动 | [本地业务闭环](rpg-minimal/README.zh-CN.md) | `mvn -q -f examples/rpg-minimal/pom.xml test exec:java` |
| 真实 TCP 收发 | [生成 Dispatcher 和 BO](rpg-tcp-generated/README.zh-CN.md) | `mvn -q -f examples/rpg-tcp-generated/pom.xml test exec:java` |
| 业务开发纵向切片 | [每日道具领取：协议、幂等、Repository、缓存、事件、日志和指标](business-slice/README.zh-CN.md) | `mvn -q -f examples/business-slice/pom.xml test exec:java` |
| 多服务器职责与玩家生命周期 | [本地角色职责、会话和独立四组件探针](multi-server-player/README.md) | `mvn -q -f examples/multi-server-player/pom.xml clean test exec:java`；外部探针运行 `examples/multi-server-player/scripts/verify-external.ps1` |
| 独立模块消费者 | [最小内核、事件/Actor、替换实现等](modular-composition/README.md) | `mvn -q -f examples/modular-composition/pom.xml test` |
| 中心—逻辑接口 | [单进程本地 RPC](modular-composition/center-logic/README.md) | `mvn -q -f examples/modular-composition/center-logic/pom.xml test exec:java` |
| Kafka 双进程 | [共享契约、center 与 logic](modular-composition/center-logic-kafka/README.md) | `mvn -q -f examples/modular-composition/center-logic-kafka/pom.xml clean install`；真实运行另需隔离 Kafka |
| Repository 替换 | [内存与外部数据来源](repository-composition/README.md) | `mvn -q -f examples/repository-composition/pom.xml test` |
| 受管定时任务 | [受管 timer、remote IO 与 Actor 执行域](managed-scheduler-local/README.md) | `mvn -q -f examples/managed-scheduler-local/pom.xml test exec:java` |
| 房间 | [创建、就绪、重连、结算](room-game/README.md) | `mvn -q -f examples/room-game/pom.xml test exec:java` |
| AOI / 状态同步 | [可见性与 snapshot/delta](aoi-state-sync/README.zh-CN.md) | `mvn -q -f examples/aoi-state-sync/pom.xml test exec:java` |
| 帧同步 | [输入、帧序与快照](frame-sync/README.md) | `mvn -q -f examples/frame-sync/pom.xml test exec:java` |
| NPC | [正式模块的生命周期与有界 tick 应用](module-recipes/npc/README.md) | `mvn -q -f examples/module-recipes/npc/pom.xml test exec:java` |
| 排行榜 / 赛季 | [排名和幂等结算](ranking-season/README.md) | `mvn -q -f examples/ranking-season/pom.xml test exec:java` |
| 世界 / 分片 | [本地迁移](world-shard/README.md) | `mvn -q -f examples/world-shard/pom.xml test exec:java` |
| TCP 登录与 KCP | [真实本机 TCP/UDP 链路](kcp-tcp-login/README.zh-CN.md) | `mvn -q -f examples/kcp-tcp-login/pom.xml compile exec:java "-Dexec.args=--demo ALL"` |
| 配置热更 | [CSV 校验和失败保旧](config-hot-reload-local/README.zh-CN.md) | `mvn -q -f examples/config-hot-reload-local/pom.xml test exec:java` |
| 日志 / 监控 | [安全字段与告警](observability-local/README.zh-CN.md) | `mvn -q -f examples/observability-local/pom.xml test exec:java` |

外部测试设施按需启动，并在测试后显式关闭：

| 环境 | 说明 | 使用说明 |
| --- | --- | --- |
| Kafka 单节点 | Kafka 3.9.1 本机隔离 broker | [启动与停止](kafka-nacos-external/README.md) |
| Kafka/Nacos 集群 | Kafka 3 节点、Nacos 2 节点与 MySQL | [启动与停止](kafka-nacos-cluster/README.md) |

默认验证不需要中间件。外部数据消费者通过各自 README 中的显式参数启用；真实 TCP 示例只使用本机临时端口。生成自己的应用见[快速上手](../docs/quickstart.zh-CN.md)，组件限制见[能力矩阵](../docs/capability-matrix.zh-CN.md)。


## 各模块 examples

每个 Reactor 模块的可复制用法入口均位于其 `examples/README.md`。能力模块提供 API 和场景入口；聚合/支撑模块说明依赖边界并链接到最近的可运行组合。

- [zero-actor](../zero-actor/examples/README.md)
- [zero-aoi](../zero-aoi/examples/README.md)
- [zero-benchmarks](../zero-benchmarks/examples/README.md)
- [zero-bom](../zero-bom/examples/README.md)
- [zero-cache](../zero-cache/examples/README.md)
- [zero-codegen](../zero-codegen/examples/README.md)
- [zero-core](../zero-core/examples/README.md)
- [zero-data](../zero-data/examples/README.md)
- [zero-data-mongo](../zero-data-mongo/examples/README.md)
- [zero-data-postgresql](../zero-data-postgresql/examples/README.md)
- [zero-data-redis](../zero-data-redis/examples/README.md)
- [zero-discovery](../zero-discovery/examples/README.md)
- [zero-discovery-nacos](../zero-discovery-nacos/examples/README.md)
- [zero-event](../zero-event/examples/README.md)
- [zero-frame-sync](../zero-frame-sync/examples/README.md)
- [zero-game](../zero-game/examples/README.md)
- [zero-gm](../zero-gm/examples/README.md)
- [zero-gm-rest](../zero-gm-rest/examples/README.md)
- [zero-hot-update](../zero-hot-update/examples/README.md)
- [zero-log](../zero-log/examples/README.md)
- [zero-logic](../zero-logic/examples/README.md)
- [zero-monitor](../zero-monitor/examples/README.md)
- [zero-net](../zero-net/examples/README.md)
- [zero-net-kcp](../zero-net-kcp/examples/README.md)
- [zero-net-kcp-redis](../zero-net-kcp-redis/examples/README.md)
- [zero-npc](../zero-npc/examples/README.md)
- [zero-ownership](../zero-ownership/examples/README.md)
- [zero-ownership-postgresql](../zero-ownership-postgresql/examples/README.md)
- [zero-parent](../zero-parent/examples/README.md)
- [zero-player](../zero-player/examples/README.md)
- [zero-protocol](../zero-protocol/examples/README.md)
- [zero-ranking](../zero-ranking/examples/README.md)
- [zero-room](../zero-room/examples/README.md)
- [zero-rpc](../zero-rpc/examples/README.md)
- [zero-rpc-common](../zero-rpc-common/examples/README.md)
- [zero-rpc-discovery](../zero-rpc-discovery/examples/README.md)
- [zero-rpc-kafka](../zero-rpc-kafka/examples/README.md)
- [zero-runtime](../zero-runtime/examples/README.md)
- [zero-runtime-actor](../zero-runtime-actor/examples/README.md)
- [zero-runtime-bootstrap](../zero-runtime-bootstrap/examples/README.md)
- [zero-runtime-cache](../zero-runtime-cache/examples/README.md)
- [zero-runtime-data](../zero-runtime-data/examples/README.md)
- [zero-runtime-discovery](../zero-runtime-discovery/examples/README.md)
- [zero-runtime-event](../zero-runtime-event/examples/README.md)
- [zero-runtime-kafka](../zero-runtime-kafka/examples/README.md)
- [zero-runtime-kcp](../zero-runtime-kcp/examples/README.md)
- [zero-runtime-log](../zero-runtime-log/examples/README.md)
- [zero-runtime-mongo](../zero-runtime-mongo/examples/README.md)
- [zero-runtime-monitor](../zero-runtime-monitor/examples/README.md)
- [zero-runtime-nacos](../zero-runtime-nacos/examples/README.md)
- [zero-runtime-net](../zero-runtime-net/examples/README.md)
- [zero-runtime-postgresql](../zero-runtime-postgresql/examples/README.md)
- [zero-runtime-production](../zero-runtime-production/examples/README.md)
- [zero-runtime-protocol](../zero-runtime-protocol/examples/README.md)
- [zero-runtime-redis](../zero-runtime-redis/examples/README.md)
- [zero-runtime-rpc](../zero-runtime-rpc/examples/README.md)
- [zero-scene](../zero-scene/examples/README.md)
- [zero-security](../zero-security/examples/README.md)
- [zero-server-starter](../zero-server-starter/examples/README.md)
- [zero-server-starter-production](../zero-server-starter-production/examples/README.md)
- [zero-state-sync](../zero-state-sync/examples/README.md)
- [zero-world](../zero-world/examples/README.md)

## 模块接入卡片

全部模块的真实源码入口集中在 [examples/module-access](module-access/README.md)，每个模块均有独立卡片，明确区分可运行场景、模块测试和装配源码，并标注验证范围。
