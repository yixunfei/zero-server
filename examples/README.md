# 可运行示例

先在仓库根目录用 JDK 21、Maven 3.9+ 执行 `mvn -B -ntp -DskipTests install`。以下命令中的 POM 均相对于仓库根目录；本地 smoke 运行后退出，Kafka 双 JVM 示例按各自生命周期显式停止。

| 场景 | 说明 | 命令 |
| --- | --- | --- |
| RPG 登录与移动 | [本地业务闭环](rpg-minimal/README.zh-CN.md) | `mvn -q -f examples/rpg-minimal/pom.xml test exec:java` |
| 真实 TCP 收发 | [生成 Dispatcher 和 BO](rpg-tcp-generated/README.zh-CN.md) | `mvn -q -f examples/rpg-tcp-generated/pom.xml test exec:java` |
| 独立模块消费者 | [最小内核、事件/Actor、替换实现等](modular-composition/README.md) | `mvn -q -f examples/modular-composition/pom.xml test` |
| 中心—逻辑接口 | [单进程本地 RPC](modular-composition/center-logic/README.md) | `mvn -q -f examples/modular-composition/center-logic/pom.xml test exec:java` |
| Kafka 双进程 | [共享契约、center 与 logic](modular-composition/center-logic-kafka/README.md) | `mvn -q -f examples/modular-composition/center-logic-kafka/pom.xml clean install`；真实运行另需隔离 Kafka |
| Repository 替换 | [内存与外部数据来源](repository-composition/README.md) | `mvn -q -f examples/repository-composition/pom.xml test` |
| 房间 | [创建、就绪、重连、结算](room-game/README.md) | `mvn -q -f examples/room-game/pom.xml test exec:java` |
| AOI / 状态同步 | [可见性与 snapshot/delta](aoi-state-sync/README.zh-CN.md) | `mvn -q -f examples/aoi-state-sync/pom.xml test exec:java` |
| 帧同步 | [输入、帧序与快照](frame-sync/README.md) | `mvn -q -f examples/frame-sync/pom.xml test exec:java` |
| NPC | [有界 tick 与行为隔离](npc-tick/README.zh-CN.md) | `mvn -q -f examples/npc-tick/pom.xml test exec:java` |
| 排行榜 / 赛季 | [排名和幂等结算](ranking-season/README.md) | `mvn -q -f examples/ranking-season/pom.xml test exec:java` |
| 世界 / 分片 | [本地迁移](world-shard/README.md) | `mvn -q -f examples/world-shard/pom.xml test exec:java` |
| 配置热更 | [CSV 校验和失败保旧](config-hot-reload-local/README.zh-CN.md) | `mvn -q -f examples/config-hot-reload-local/pom.xml test exec:java` |
| 日志 / 监控 | [安全字段与告警](observability-local/README.zh-CN.md) | `mvn -q -f examples/observability-local/pom.xml test exec:java` |

默认验证不需要中间件。外部数据消费者通过各自 README 中的显式参数启用；真实 TCP 示例只使用本机临时端口。生成自己的应用见[快速上手](../docs/quickstart.zh-CN.md)，组件限制见[能力矩阵](../docs/capability-matrix.zh-CN.md)。
