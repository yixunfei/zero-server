# Repository 业务接入与实现替换

业务服务只依赖 `CrudRepository<ID, T>`。组合根通过 `RepositoryDefinition` 描述实体和 codec，通过 `RepositoryRequest` 指定业务角色，再由 `RepositoryCatalog` 将角色映射到命名数据源。连接客户端由 Adapter 持有，业务无需驱动 getter 或具体 Adapter 强转。

## 1. 依赖与装配

| 实现 | 应用引入的集成模块 | 安装模块 | 数据源名 |
| --- | --- | --- | --- |
| 本地内存 | `zero-runtime-bootstrap`、`zero-runtime-data` | `DataRuntime.module()` | `local` |
| MongoDB | `zero-runtime-mongo` | `MongoRuntime.module()` | `mongo` |
| PostgreSQL | `zero-runtime-postgresql` | `PostgresqlRuntime.module()` | `postgresql` |
| Redis 数据 | `zero-runtime-redis` | `RedisRuntime.module()` | `redis` |

本地装配：

```java
GameRuntime runtime = RuntimeBasics.builder()
        .install(DataRuntime.module())
        .install(DataRuntime.repositories(Map.of("game", "local")))
        .build();
```

MongoDB 装配（配置需显式启用 Adapter 并提供 URI、数据库）：

```java
GameRuntime runtime = ProductionAssembly.builder(config)
        .install(MongoRuntime.module())
        .install(DataRuntime.repositories(Map.of("game", "mongo")))
        .build();
```

可以同时安装多个数据源，例如将 `game` 绑定 `mongo`，`audit` 绑定 `postgresql`。没有隐式默认源，也不会取数据源集合的第一项。Redis 数据按本节 Repository 契约接入，Redis 缓存通过 `CacheRuntime.CACHE_SERVICE` 接入，两者的配置开关独立。

## 2. 业务入口

```java
RepositoryDefinition<String, Balance> definition = RepositoryDefinition.of(
        "balances", String.class, Balance.class, new BalanceCodec(), 1);
CrudRepository<String, Balance> repository = runtime.require(DataRuntime.REPOSITORIES)
        .create(new RepositoryRequest<>("game", definition));
BalanceService service = new BalanceService(repository);
```

`Balance` 实现 `VersionedEntity<String>`，复用既有 `@ZeroDataObject`、字段映射和 `ZeroPayloadCodec`。`RepositoryDefinition` 校验实体类型、ID 类型及 codec 类型/版本。同名 Repository 复用已创建实例；定义应作为不可变常量复用，不能用另一个 codec 实例或不同版本重新绑定同名 Repository。同一工厂也拒绝另一个名称指向已绑定的 namespace/collection。

`RepositoryFactory` 允许自定义实现；应用 provider 向 `DataRuntime.REPOSITORY_SOURCES` 贡献 `RepositorySource`，再显式配置角色。工厂内部可以复用 `EnvelopeRepositoryFactory`，也可返回满足 `CrudRepository` 契约的其他实现。

## 3. 生命周期与失败

- Repository 按业务首次请求延迟创建，驱动客户端保持在 Adapter 的资源账本内。
- runtime 关闭或构建回滚时，先关闭工厂访问，再释放客户端；已保留的 Repository 后续访问以 `BACKEND_UNAVAILABLE` 失败。
- 关闭工厂会等待正在执行的同步 store 操作；不同 store 访问可并行。客户端自身的超时仍需按应用要求配置。
- 角色缺失、源名称重复、定义冲突不会隐式降级。底层存储异常经统一错误边界处理，避免输出连接信息。
- 既有 envelope、codec 和 CAS 版本规则保持不变。成功保存推进版本，旧版本写入仍报告 `VERSION_CONFLICT`。
- 当前工厂创建与 envelope store 调用可能同步阻塞，即使接口返回 `CompletionStage`。真实存储操作应进入受管远程 IO 执行域，不应放在 Actor 热路径。

## 4. 可运行示例

```powershell
mvn -B -ntp -q -DskipTests install
mvn -B -ntp -q -f examples/repository-composition/pom.xml clean test exec:java
```

输出 `repository-composition=ok|backend=local|amount=15|version=2`。同一个 [BalanceService](../examples/repository-composition/src/main/java/group/zn/zero/examples/repository/BalanceService.java) 执行两次余额增加，组合根可以切换四种来源。

默认验收包含四种本地 envelope 表示、角色与类型校验、CAS、关闭后访问、并发关闭和后续 provider 创建失败回滚。没有连接真实 MongoDB、Redis 或 PostgreSQL。外部配置、显式运行命令与示例记录清理范围见[示例 README](../examples/repository-composition/README.md)。

## 5. 真实驱动契约验证

仓库提供独立入口，使用 Docker 创建本轮专用 MongoDB 7.0、PostgreSQL 16、Redis 7.2，并执行同一业务契约：

```powershell
./scripts/VerifyRepositoryDrivers.ps1 -Plan
./scripts/VerifyRepositoryDrivers.ps1
```

前置条件为 PowerShell 7、Java 21、Maven 和正在运行的 Docker；先安装当前 SNAPSHOT。数据库只绑定随机本机端口。验证包括 CRUD、旧版本写入冲突、不同 namespace 使用相同 ID、关闭后拒绝访问，以及真实实现重建客户端后读取已保存记录。本地内存不承诺关闭后持久化。失败不会按跳过处理，日志与镜像 ID 保存在 `target/repository-driver-verify/<run>/`。

每个测试使用唯一 namespace，仅删除自己的记录；容器及卷仅按本轮 Compose project 清理。此入口不属于默认本地验收。停启恢复与持续运行使用下节的显式扩展入口。

MongoDB 的物理集合名采用 `z_<namespace 的 UTF-8 十六进制>__<collection 的 UTF-8 十六进制>`，避免 `tenant-a` 与 `tenant_a`、分隔符边界等碰撞。数据库名加点分隔符和编码后的集合名总长不得超过 235 字节，非法 Unicode 和超长名称在创建时拒绝。该规则改变物理名称，已有开发数据应显式迁移或重建，不自动读取旧集合。

PostgreSQL production provider 要求 URL、用户名、密码和表名全部显式配置；`ZERO_POSTGRESQL_TABLE` 为必填。

PostgreSQL 集成模块使用 runtime 管理的 HikariCP 连接池，默认最多 8 个物理连接，可通过 `PostgresqlRuntime.module(16)` 调整。选择 provider 后才创建池，首次借连接时才打开物理连接；runtime 先失效 Repository 访问，再关闭池。借连接等待使用 Adapter timeout，JDBC URL 的 `connectTimeout`、`socketTimeout` 仍控制驱动连接和 socket 操作，应用需按运行要求配置。

HikariCP 仅属于 `zero-runtime-postgresql` 的依赖，`zero-data-postgresql` 接收标准 `javax.sql.DataSource`。应用自带连接池时可在自定义 provider 中使用 `PostgresqlDataAdapter.repositoryFactory(dataSource, tableName)`，并由组合根登记、关闭数据源；工厂不接管调用方传入的数据源。借出的连接必须开启自动提交，手动事务连接会在执行语句前被拒绝并归还，不擅自提交调用方事务。仅传 driver settings 的直接 Adapter 入口仍按操作建连，适用于低频工具，不应作为持续请求路径。

## 6. 停启恢复、并发和持续运行

```powershell
./scripts/VerifyRepositoryDrivers.ps1 -Plan -Resilience
./scripts/VerifyRepositoryDrivers.ps1 -Resilience
./scripts/VerifyRepositoryDrivers.ps1 -Resilience -SoakSeconds 1800
```

此入口先执行基础契约，然后依次验证外部服务停启、四种实现的并发 CAS，以及每种实现默认 300 秒的持续运行。持续运行阶段的四个后端同时执行，每个后端 4 个工作线程，每次入账后停顿 10 ms；时长可配置为 1 至 86400 秒。它是有限负载下一致性检查，不用于比较容量。

停启使用同一个 runtime、客户端和 Repository：服务停止时读取须以 `READ_FAILED` 失败，正常启动后读到原值，随后继续写入。脚本随机选取空闲本机端口后固定整轮绑定，并检查重启前后端点一致；端口绑定发生竞争时明确失败。只允许操作本轮唯一且标签匹配的 Compose 容器。Redis 测试服务显式配置 AOF 和 `appendfsync always`；结论限于正常停止/启动，不覆盖断电、强杀或网络分区。

并发检查包含同版本同时创建与更新，每轮恰好一个成功；随后检查累计入账数、余额和版本一致。外部实现使用四个独立 runtime/客户端，避免单实例同步锁掩盖驱动 CAS 问题。本地实现按实际语义共享同一 runtime 的内存 Repository，不承诺跨 runtime 共享。

测试仅对明确的 `VERSION_CONFLICT` 做有上限的重读重试，其他错误立即失败。生产业务应自行决定冲突处理策略；网络超时后的写入结果可能不确定，不能直接重复非幂等入账。Mongo/Redis 测试客户端使用既有 Adapter 配置将原生超时设为 2 秒，PostgreSQL 测试 URL 显式配置 2 秒连接和 socket 超时。默认应用配置不受影响。

日志与 Failsafe 摘要仍写入唯一运行目录；`resilience.log` 包含每 30 秒进度、成功数、冲突数、含重试的平均/最大延迟。五分钟验证不能证明小时级资源稳定性，也不覆盖请求执行中断网和写入结果对账。最新实际结果与下一步见[推进方案](demand-driven-composition.zh-CN.md)。
