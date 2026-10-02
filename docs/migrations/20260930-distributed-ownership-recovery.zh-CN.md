# 2026-09-30 跨进程 ownership、迁移恢复与容量治理

## 变更

新增可独立引入的 `zero-ownership` 模块。核心端口包括：

- `OwnershipStore`：按资源类型、资源 ID 和 generation 管理 lease；`ProcessIncarnation` 与递增 epoch 用于旧进程 fencing；实现必须提供权威 `assertCurrent`，不能用本地时钟默认替代。
- `FencedStateStore`：提交端再次校验 owner、epoch、租约有效期，并用 `StateVersion` 做 CAS；`requestId` 重试返回原结果，复用 ID 但改变 payload 会拒绝。
- `MigrationStore`：`REQUESTED -> FROZEN -> CHECKPOINTED -> COMMITTED -> ACTIVATED -> COMPLETED` 的单向状态机，所有状态记录可查询并可从中断点恢复；任意中间阶段可显式 `ABORTED`。
- `FailoverCoordinator`：显式执行 checkpoint 提交、过期租约接管、激活 fencing 和完成/中止；若接管已提交但回包丢失，重试会查询并复用目标节点当前 lease，不能把通知发送成功当作 ownership 或状态提交成功。
- `OwnershipRouter`：可选的 owner endpoint 路由端口；路由与 lease 的 owner/epoch/有效期绑定，旧 lease 不能覆盖或撤销新 owner 路由。内存实现仅用于原型和测试，PostgreSQL Adapter 提供独立路由表实现。
- `MigrationCheckpointMetadata`：迁移记录保存 schema/codec 版本及历史完整性，目标节点可据此决定恢复或触发 resync；不强制具体 payload 格式。
- `CapacityBudget`：有界并发、有限等待队列、调用方 deadline、停止准入、限时等待 drain 和 close；`CapacityBoundStateStore` 可选地为任意状态端口增加有界准入；不创建后台线程。

这些是基础设施能力，不以 World 或 Room 为公共抽象；两个领域都只是可选消费示例，也不需要依赖本模块。`zero-ownership` 只依赖 JDK；`zero-ownership-postgresql` 是单独显式添加的 JDBC Adapter，驱动不会从核心模块传递引入。Kafka、Nacos 和监控仍然独立可选，没有被 ownership 模块绑定。

PostgreSQL Adapter 提供 `PostgresqlOwnershipStore`、`PostgresqlFencedStateStore`、`PostgresqlMigrationStore` 和 `PostgresqlOwnershipRouter`，共享调用方提供的 `DataSource`，并以可配置表名前缀初始化独立 schema 对象。lease 使用行锁、数据库时钟和递增 epoch；fenced commit 在单个事务内校验 lease、执行版本 CAS、保存结果及 requestId；迁移状态行锁后校验单向状态转换并持久化 checkpoint 元数据；路由发布在同一连接内再次校验 lease。payload 编码由应用注入 `OwnershipPayloadCodec`，因此不强制 JSON 或其他业务序列化格式。Adapter 不负责连接池、schema 版本编排、数据库复制集或消息通知。

## 迁移与兼容

`0.x` 阶段新增模块不改变现有模块默认依赖。应用需要跨进程 ownership 时显式添加 `zero-ownership`，选择合适的 `OwnershipStore`、`FencedStateStore` 和 `MigrationStore` 实现，并在权威提交端执行 fencing。不能把 RPC oneway 发送完成当作状态提交成功，也不能只在入口进程检查 epoch。

资源重建必须递增 generation；进程重启必须生成新的 incarnation。迁移恢复时读取持久化 `MigrationRecord`，可调用 `FailoverCoordinator.recover(...)` 从 `CHECKPOINTED`/`COMMITTED`/`ACTIVATED` 继续，不能跳过 checkpoint 或把旧 owner 重新激活。路由只作为转发提示，权威写入仍必须通过 fenced state store。

## 验证证据与边界

已在 Java 21 下通过：

```text
mvn -B -ntp -q -pl zero-ownership -am test
```

覆盖 lease 接管和 epoch 单调性、generation 隔离、旧 incarnation 拒绝、fenced CAS、requestId 幂等与冲突、迁移非法跳转/恢复、路由 epoch fencing、容量上限/deadline/drain 和容量装饰器。当前证据是单 JVM 内存实现和契约测试，未证明数据库主从切换、Kafka broker 故障转移或生产容量；`productionReady=false`。

PostgreSQL Adapter 和真实外部验证：

```text
mvn -B -ntp -Pexternal-tests -pl zero-ownership-postgresql -am verify
```

执行前设置 `ZERO_EXTERNAL_POSTGRES_URL`、`ZERO_EXTERNAL_POSTGRES_USER` 和 `ZERO_EXTERNAL_POSTGRES_PASSWORD_FILE`。外部测试默认不参与普通测试；每次测试使用独立表名前缀，不清理数据库卷。已在隔离 PostgreSQL 单实例验证两个 store 竞争 ownership、两个独立 JVM 竞争同一资源、过期接管递增 epoch、旧 lease 写入被 fencing、state CAS/requestId replay、路由发布/撤销 fencing，以及迁移记录（含 checkpoint 元数据）由新 store 实例读取和续转。此证据不覆盖数据库网络分区、主从提升、备份恢复、跨机房、长稳和生产容量，`productionReady=false`。

`M:\docker_space` 中的 PostgreSQL、Redis、MongoDB 仅作为后续 Adapter 集成测试环境，不能据此声称核心模块具备持久化故障转移。不得删除既有卷或使用 `down -v`。
