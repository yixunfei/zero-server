# RPG 最小本地示例

本示例展示如何在不启动 Docker、不连接 Kafka / MongoDB / Redis / PostgreSQL / Nacos 的情况下，组合 zeroServer 本地 starter、玩家基础服务和场景基础服务，跑通一个最小 RPG 流程。

```text
local starter
  -> 玩家登录
  -> 玩家在线数据加载
  -> 进入场景
  -> 场景移动
  -> GM 查询玩家和场景
  -> 离开场景
  -> 输出日志、指标和装配摘要
```

示例同时包含两条入口：

- `RpgMinimalApplication`：直接手写 Java 调用玩家与场景服务，适合理解 starter 组件装配。
- `RpgProtocolApplication`：从 `src/main/protocol/Rpg.si` 生成 DTO、codec、BO 和 dispatcher，再由手写 BO 驱动玩家与场景服务，适合理解协议驱动业务开发。

## 1. 运行方式

先在仓库根目录安装当前 SNAPSHOT 工件：

```powershell
mvn -q -DskipTests install
```

然后运行示例：

```powershell
mvn -q -f examples/rpg-minimal/pom.xml test
mvn -q -f examples/rpg-minimal/pom.xml exec:java
```

输出类似：

```text
rpg-minimal=ok|mode=local|name=rpg-minimal|uid=1001|player=player-1001|position=7,11|sceneBefore=1|sceneAfter=0|leaveRemoved=true|logs=6|metrics=5
```

运行协议驱动入口：

```powershell
mvn -q -f examples/rpg-minimal/pom.xml "-Dexec.mainClass=group.zn.zero.examples.rpg.RpgProtocolApplication" exec:java
```

输出类似：

```text
rpg-protocol=ok|mode=local|name=rpg-protocol|uid=1001|player=player-1001|position=7,11|sceneBefore=scene=scene-1|entities=1|first=1001@7,11|sceneAfter=scene=scene-1|entities=0|leaveRemoved=true|logs=9|metrics=7|maxProtocolId=80113
```

## 2. 示例说明

示例源码位于：

```text
examples/rpg-minimal/src/main/java/group/zn/zero/examples/rpg/RpgMinimalApplication.java
examples/rpg-minimal/src/main/java/group/zn/zero/examples/rpg/RpgProtocolApplication.java
examples/rpg-minimal/src/main/protocol/Rpg.si
```

它演示了以下做法：

- 使用 `LocalRuntime.builder(...)` 创建中立 `GameRuntime`。
- 使用 `ZeroRuntimeExecutors.localPrototype(...)` 让 starter 管理原型执行域。
- 使用 `LocalPlayerService` 处理登录、玩家加载和 GM 查询玩家。
- 使用 `LocalSceneService` 处理进入场景、移动、场景实体查询和离开场景。
- 使用 `InMemoryLogSink` 作为本地终端快照，业务日志经 `LogAppender` 安全入口写入，并由 `MonitorRuntime` 记录指标。
- 通过 `GameRuntime.require(...)` 与 `ActorRuntime`、`RuntimeBasics`、`LogRuntime` 的能力键取得 Actor、配置和安全日志。

协议驱动入口还演示了以下做法：

- Maven `generate-sources` 阶段调用 `ProtocolCodegenCli`。
- 将 `target/generated-sources/zero-codegen` 加入示例编译源码目录。
- 使用生成的 `Rpg*ProtocolDTO` 和 `Rpg*ProtocolDTOCodec` 构造协议 payload。
- 使用生成的 `GeneratedProtocolDispatcher` 分发协议。
- 手写业务类实现生成的 `Rpg*EventBO` 接口，把协议事件接入玩家与场景服务。

## 3. 当前边界

本示例是 local / prototype 示例，不是生产部署模板：

- 不开放网络端口。
- 不连接真实中间件。
- 不实现账号鉴权、渠道登录、AOI、广播、帧同步、NPC 或跨服路由。
- 不承诺生产性能、队列背压、缓存一致性或长稳容量。

后续如果需要从示例进入真实业务开发，建议先从 `.si` 协议声明开始，生成 DTO、codec、BO 和 dispatcher，再把业务逻辑接入玩家、场景、数据、缓存、日志和指标抽象。当前示例使用 `zero-codegen` 作为构建期工具；生产运行时不应依赖 GUI 或代码生成流程。
