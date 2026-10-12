# 多服务器职责与玩家生命周期示例

本例在**单 JVM 的普通命令行线程**中演示登录服、中心服和逻辑服职责：账号注册、教学认证、粘性路由、创建角色、缓存回源、断线与重连。当前运行路径为：

```text
MultiServerPlayerApplication
  → LoginServer → PlayerLifecycleService → 框架 LocalPlayerService / Actor
  → CenterRouter（UID 的稳定逻辑服绑定）
  → LogicServer → 内存 Repository + CacheService
```

`rpc/*Contract` 是职责划分草图，尚未装配 codec、远端 handler、认证和进程通信。`RoleLauncherMain` 只输出角色接入提示，不启动服务器。完整双 JVM Kafka 装配请参考 [center-logic-kafka](../modular-composition/center-logic-kafka/README.md)。

## 本地运行

仓库根目录使用 JDK 21、Maven 3.9+：

```powershell
mvn -B -ntp -DskipTests install
mvn -B -ntp -f examples/multi-server-player/pom.xml clean test exec:java
```

无需外部服务。输出包含注册账号、首次/重连会话及角色名称；两个会话 ID 应不同。教学 token 固定为 `token-账号`，不用于真实认证。

[PlayerLifecycleService](src/main/java/group/zn/zero/examples/multiplayer/PlayerLifecycleService.java) 的所有业务方法必须在创建线程调用；违规调用在修改状态前返回明确错误。框架内部仍通过 Actor 修改会话。这里的 join 是命令行编排，不可直接挂到 Actor、Netty 或 Kafka 工作线程。

账号和 UID 分配仅存在于本次内存实例；重启不会保留。若接入持久化或多进程，需要独立的权威账号/UID 分配、所有权约束、受管 IO 与异步业务编排，不能只替换两个 Repository/Cache 实现。

## 业务约束与回归

- 字符串哈希冲突不会使不同账号共用 UID。
- 重复登录复用会话和逻辑服路由；新节点注册不会迁移已有 UID。
- 重连先校验全部输入并认证，失败不踢旧会话；旧断线消息不能清除新会话。
- 创建角色前校验完整当前身份；重复创建拒绝覆盖，返回和缓存使用仓库提交后的版本。
- 注册和路由使用同一实例表；本例不探测逻辑服健康、不实现迁服。
- 业务异常携带 ErrorCode，角色接口返回 RpcResult.failure；未知基础设施异常仍交上层处理。
- RPC 注解中的 idempotent 表达重复请求不会重复写入，不提供跨进程去重或相同结果重放。

[PlayerLifecycleServiceTest](src/test/java/group/zn/zero/examples/multiplayer/PlayerLifecycleServiceTest.java) 覆盖以上约束，并通过显式 invalidate 验证缓存回源。

## 四组件连通探针

[ExternalInfrastructureProbe](src/main/java/group/zn/zero/examples/multiplayer/ExternalInfrastructureProbe.java) 验证 Kafka metadata、MongoDB/Redis 写后读与删除、Nacos 注册查询与注销。每次使用随机探针身份，失败会返回非零退出；这不验证上述玩家流程已跨进程运行，也不验证 Mongo/Redis Repository 映射。

PowerShell 7 在仓库根目录执行：

```powershell
.\examples\multi-server-player\scripts\verify-external.ps1
```

脚本使用独立 Compose 项目，部署配置、日志与环境记录默认写入 `M:\docker_space\containers\<项目名>`。运行前核验本机 Docker 引擎磁盘位置与端口。使用本地已有版本镜像；模板仅绑定 loopback，不启用业务认证。

默认端口：Kafka 19092、Nacos HTTP 18848/gRPC 19848、MongoDB 27018、Redis 16379。已有监听时直接报错，不停止其他服务。脚本会编译并通过独立 JVM 运行探针，避免 SDK 全局线程与 Maven classloader 的退出冲突。

正常或失败退出时，默认移除本次容器和网络，**保留具名卷**，不执行 down -v。追加 `-Keep` 保留容器；停止命令写入环境记录。连接环境变量在脚本退出时恢复。数据卷需要使用者另行决定清理。

要验证已存在的隔离环境，先设置 `ZERO_KAFKA_BOOTSTRAP`、`ZERO_NACOS_ADDRESS`、`ZERO_MONGO_URI`、`ZERO_MONGO_DATABASE`、`ZERO_REDIS_URI`，再运行：

```powershell
.\examples\multi-server-player\scripts\verify-external.ps1 -UseExisting
```

此模式要求五个变量齐全，不调用 Docker、不覆盖连接变量。示例组合根只读取这些环境变量，不接受 JVM 的 zero.mongo/zero.redis 属性覆盖。Nacos 探针当前面向无鉴权的本机测试环境。

直接运行探针的等价命令为 `mvn -f examples/multi-server-player/pom.xml -Pexternal-probe compile exec:exec`。仅所有组件通过时输出 `MULTI_SERVER_PLAYER_EXTERNAL_OK`。