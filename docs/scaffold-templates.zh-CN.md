# zeroServer 脚手架模板目录

本文用于回答一个更接近业务开发的问题：

```text
我要做某类游戏服务器原型，应该从哪个模板开始？
```

当前提供七类业务原型和一个按需 `runtime` 模板。业务模板默认组合协议生成、Actor、日志和监控；`runtime` 从配置与执行器起步，可显式添加组件和替换实现。模板不是生产部署证明；选中外部 Adapter 后仍需真实服务验证。

## 1. 快速选择

| 需求 | 模板参数 | 适合验证 | 主要生产缺口 |
| --- | --- | --- | --- |
| 最小内核 / 自选组件 | `--template runtime` | 最小依赖、显式装配、实现替换 | 外部服务与部署验证 |
| RPG 最小本地流程 | `--template local` | 登录、进入场景、移动、玩家 / 场景服务组合 | 账号鉴权、断线重连、正式在线状态和持久化策略 |
| 房间 / 对战小局 | `--template room` | 创建房间、加入、准备、开始、提交帧输入 | 匹配、广播、断线恢复、观战、结算和跨服房间；模块用法见 [房间组件本地契约与扩展边界](reference/room-component-minimum-contract.zh-CN.md) |
| 场景同步 / 简单 AOI | `--template scene-sync` | 进入场景、移动、可见性查询 | 正式 AOI、广播、delta 压缩、快照协议和跨服迁移；模块用法见 [AOI / 状态同步本地契约与扩展边界](reference/aoi-state-sync-minimum-contract.zh-CN.md) |
| 帧同步 / lockstep | `--template frame-sync` | 加入比赛、提交输入、推进帧、查询快照 | 时钟模型、断线补帧、回滚、观战、可靠广播和反作弊；模块用法见 [帧同步本地契约与扩展边界](reference/frame-sync-minimum-contract.zh-CN.md) |
| AI NPC / tick | `--template npc-tick` | NPC 生成、行为切换、zone tick、状态查询 | 行为树、寻路、战斗 AI、tick 预算、背压和跨服迁移；模块用法见 [NPC tick 本地契约与扩展边界](reference/npc-tick-minimum-contract.zh-CN.md) |
| 排行榜 / 赛季 | `--template ranking-season` | 积分提交、Top 查询、玩家排名、赛季切换 | Redis sorted set、跨服榜、结算奖励、幂等和容量验证；模块用法见 [排行榜 / 赛季本地契约与扩展边界](reference/ranking-season-minimum-contract.zh-CN.md) |
| 开放世界 / 分片迁移 | `--template world-shard` | 进入世界、实体移动、分片迁移、状态查询 | 跨进程迁移、状态交接、跨服广播、AOI 拼接和一致性协议；模块用法见 [开放世界 / 分片迁移本地契约与扩展边界](reference/world-shard-minimum-contract.zh-CN.md) |

脚本事实入口以命令输出为准：

```powershell
java scripts/NewLocalGame.java --listTemplates
```

### local 模板的异步边界

`local` 模板生成的业务代码拆分为 composition root、`LocalGameBO`、`LocalGameFlow`、`LocalGameFixture` 和 `LocalGameObservation`。Player/scene 服务通过构造器注入，返回 `CompletionStage`；`LocalGameBO` 和 Actor/IO 路径禁止调用 `join()`/`get()`。只有最外层 `runDemo` smoke 编排允许等待最终结果，不能将其视为长驻网络服务或生产就绪证明。超时、取消、重复请求和异常语义由业务端口显式定义。

## CLI 退出码契约

`ProjectScaffoldCli` 作为进程运行时使用稳定退出码：

- `0`：成功（包括只读 catalog 查询）。
- `1`：普通生成失败；stderr 以 `SCAFFOLD-GENERATION-FAILED|<message>` 开头。
- `2`：参数解析失败；stderr 以 `SCAFFOLD-INVALID-ARGUMENT|<message>` 开头。
- `3`：`--plan`/`--diff` 或升级事务被阻塞；stderr 以 `SCAFFOLD-PLAN-BLOCKED|<message>` 或对应稳定升级错误码开头。

这些错误文本写入 stderr，stdout 仅保留成功摘要或 plan 输出。进程级 0/1/2/3 矩阵由 `ZeroAcceptanceEvidence` 在独立子进程中执行，并将每个场景的 stdout、原始 stderr、预期/实际退出码保存到 `target/acceptance-evidence/cli-exit-matrix/`。


## 2. 按关键词选择

如果不想先读完整目录，可以直接用关键词让脚手架推荐模板：

```powershell
java scripts/NewLocalGame.java --recommend "aoi scene sync"
java scripts/NewLocalGame.java --recommend "ranking season"
java scripts/NewLocalGame.java --recommend "open world shard"
```

推荐输出会给出候选模板、匹配得分、用途、运行摘要、生产缺口和建议生成命令。它是启发式入口，不替代本文的完整边界说明。

如果想直接按关键词生成项目，可以使用 `--fromKeywords`。该参数会选择匹配得分最高的模板；没有直接匹配时回退到 `local`。它不能和 `--template` 同时使用。

```powershell
java scripts/NewLocalGame.java `
  --fromKeywords "open world shard" `
  --projectName my-world-game `
  --packageName group.zn.zero.generated.myworld `
  --outputDir target\my-world-game
```

查看单个模板详情：

```powershell
java scripts/NewLocalGame.java --describeTemplate scene-sync
```

详情输出包含模板说明、适用场景、协议文件、运行摘要、生产缺口、关键词和生成命令。

## 3. 统一生成方式

所有模板都使用同一套命令形态：

```powershell
java scripts/NewLocalGame.java `
  --template <template> `
  --projectName my-game `
  --packageName group.zn.zero.generated.mygame `
  --outputDir target\my-game
```

七类默认业务模板生成后执行：

```powershell
java scripts/RunLocalScaffold.java --projectDir target/my-game
```

如果想用一条显式命令完成生成、结构检查、Maven test 和运行 smoke，可以使用：

```powershell
java scripts/RunLocalPrototype.java `
  --fromKeywords "open world shard" `
  --projectName my-world-game `
  --packageName group.zn.zero.generated.myworld `
  --outputDir target\my-world-game `
  --force
```

如果没有指定 `--template` 或 `--fromKeywords`，默认使用 `local`。

七类业务模板的生成项目都包含：

- `src/main/protocol/*.si` 和 `protoId.txt`。
- Maven `generate-sources` 阶段 codegen。
- 生成 DTO、codec、BO、dispatcher。
- 手写 BO 实现。
- 独立 `RuntimeAssembly.java` 装配 Actor、日志和监控；业务只保存安全 `LogAppender`，指标定义显式声明有序标签 schema。
- 一个 smoke test。
- `README.md`，说明当前模板边界，并在 `Next Business Step` 中交接到 `BUSINESS_GUIDE.md` 的 `First Business Change`。
- `BUSINESS_GUIDE.md`，说明业务开发入口、协议修改位置、手写 BO 逻辑位置、本地验证命令、按模板差异化的第一个业务改动 recipe 和生产边界。
- `COMPONENTS.md`，说明生成协议流、框架组件触点和生产晋升边界。
- `NEXT_STEPS.md`，说明生产晋升缺口、推荐命令和高风险暂停点。
- `zero-scaffold.json`，提供模板名称、协议文件、摘要前缀、组件触点和原型边界等机器可读元数据。

## 按需组件选择

先运行 `mvn -B -ntp -q -DskipTests install` 安装本次框架与生成工具。`--components` 接收逗号分隔的组件 ID，附加到模板必须能力上。未知 ID 会在写入工程前失败。

支持：`bootstrap`、`actor`、`event`、`protocol`、`rpc`、`data`、`cache`、`log`、`monitor`、`discovery`、`redis`、`kafka`、`nacos`、`mongo`、`postgresql`、`net`、`custom-actor`。基础配置/执行器始终存在，组件依赖自动补齐。`redis` 选择 Redis 数据工厂；`custom-actor` 用应用 provider 替换 Actor 调度器。`kafka`/`nacos` 替换同次选择的本地 `rpc`/`discovery`。`runtime + net` 生成网络策略依赖与配置；`local + net` 额外生成 `<Application>Server` 与 `<Application>TcpClient`，必须显式运行 Server 才监听。生成的 `RuntimeAssembly` 启用网络生命周期 provider，使用拒绝握手的占位策略与框架内置有界限流；接入前必须提供业务握手、鉴权与安全链。`runtime + net` 不依赖聚合 Starter，只有 local TCP 示例需要它。默认无 net 的 smoke 入口仍会退出；示例见[快速上手](quickstart.zh-CN.md#3-生成并运行业务原型)。

| 路径 | 生成参数 | 默认验收行为 |
| --- | --- | --- |
| 最小运行时 | `--template runtime` | 仅 3 个框架依赖，启动后关闭 |
| 事件 / Actor | `--template runtime --components event,actor` | 7 个框架依赖，启动后关闭 |
| 本地 RPG | `--template local` | 登录、场景进入与移动后退出 |
| TCP 原型 | `--template local --components net` | Server/客户端辅助类可生成；显式运行本地 Server，业务安全接线仍需应用提供 |
| 单 Redis | `--template runtime --components redis` | 仅诊断，不创建客户端，输出 `started=false` |
| 中心—逻辑 RPC | `--template runtime --components kafka` | 自动补齐日志，未选数据库/发现 SDK 缺席 |
| 分布式基础设施组合 | `--template runtime --components kafka,nacos,mongo,redis,postgresql` | 仅诊断所选配置，不连接真实服务 |
| 自定义实现 | `--template local --components custom-actor` | 同一业务源码使用 `LocalActorScheduler` |

```powershell
java scripts/NewLocalGame.java --template runtime --components event,actor --projectName my-runtime --outputDir target/my-runtime
mvn -q -f target/my-runtime/pom.xml clean test exec:java
java scripts/VerifyGeneratedCompositions.java
```

POM 由所选 provider 的 Maven 坐标及模板源码需要组成。RPG 的 player/scene 仍传递需要 game、data、cache 抽象；这些类存在不代表安装了对应运行时 provider。其他六类业务模板不会固定引入 player/scene。codegen 是 exec 插件依赖，不进入应用运行类路径。最小 `runtime` 模板没有协议文件或协议构建插件。

每个工程还生成 `config/application.properties.example` 和 `RuntimeAssembly.java`，清单增加 `selectedComponents`、`selectedProviders`、`runtimeCapabilities`。一个数据来源绑定角色 `main`；多个来源按来源名绑定，应用可改为业务角色。业务接入见 [Repository 指南](guides/repository-composition-guide.zh-CN.md)。

配置文件通过 `ZERO_CONFIG_FILE` 或 `-Dzero.config.file` 交给 `ZeroConfigLoader`。外部 runtime 模板默认只诊断，不创建客户端；缺配置输出 `runtime-diagnosis=incomplete`，齐全则为 `ok`。使用样例配置后执行 `mvn -q exec:java '-Dexec.args=--start'` 才进行真实启动健康检查；`zero.mode` 可为 standalone/external-test/production。给业务模板额外添加 Adapter 后，业务启动及测试需要真实配置和服务，不属于七类默认本地 smoke。

`VerifyGeneratedCompositions` 覆盖 25 个消费者，独立编译、测试和运行，检查代表场景的完整框架依赖集合、每个组件及混合组合的 SDK 缺席/存在，并在三种外部档位下比较实际 provider 图与清单。外部图验证不创建客户端。七类默认业务回归仍使用 `VerifyLocalScaffolds`；两者均纳入阶段 0 full。

`RunLocalScaffold` 会先调用 `InspectLocalScaffold` 快速检查项目结构、manifest、协议入口和 local/prototype 边界，再执行 Maven `clean test` 和 `exec:java`，并校验输出摘要。它不替代 external-tests、压测或生产验收。

如果准备把生成项目中的能力抽取为正式框架模块，或修改公共 API、线程模型、协议、RPC、存储、权限与日志字段，应先在 GitHub 提交 Design Proposal，说明现状、目标、非目标、接口草图、依赖方向、兼容性、性能、安全、迁移和验证方案。

七种完整模板当前统一使用首版日志字段模型：`LogSource + LogOperation + ZeroLogRecord.create(...)`，业务观察点持有 `LogAppender`，扩展字段使用 `fields`。模板指标都显式声明有序 `labelNames`，样本 key 必须与定义完全匹配。模板仍只演示 local/prototype，不因字段迁移获得生产日志落地或容量证明。

如果要从模板继续推进正式模块设计，请先阅读生成项目的 `BUSINESS_GUIDE.md` 与 `NEXT_STEPS.md`，再按 [贡献指南](../CONTRIBUTING.md) 提交 Design Proposal。

## 4. 模板详情

### 4.1 local

适合：

- RPG 或普通在线游戏的最小本地服务端原型。
- 理解登录、玩家、场景、日志和指标如何在单进程内组合。

生成协议：

```text
Game.si
  login(accountId, token, traceId)
  enterScene(uid, sceneId, traceId)
  move(uid, sceneId, x, y, traceId)
```

运行摘要前缀：

```text
local-game=ok
```

框架能力触点：

- `zero-codegen` 生成 DTO / codec / BO / dispatcher。
- 默认通过生成的 `RuntimeAssembly` 按需装配；选择 `net` 时 Server 使用 `ZeroServerTcpApplication` 管理 runtime/listener 生命周期。
- `zero-player` 和 `zero-scene` 提供玩家与场景原型能力。
- `zero-log` 和 `zero-monitor` 记录本地日志与指标。

生产缺口：

- 不包含真实账号鉴权、渠道登录、断线重连和正式在线状态管理。
- 不承诺生产持久化、容量或长稳行为。

### 4.2 room

适合：

- 回合制、对战小局、桌游、轻量匹配房间原型。
- 理解房间状态如何通过 Actor lane 串行化修改。

生成协议：

```text
Room.si
  createRoom(ownerUid, roomId, traceId)
  joinRoom(uid, roomId, traceId)
  ready(uid, roomId, traceId)
  startMatch(roomId, traceId)
  submitFrame(uid, roomId, frame, input, traceId)
```

运行摘要前缀：

```text
room-game=ok
```

框架能力触点：

- generated BO 接入本地房间状态对象。
- Actor lane 按房间维度串行化状态变更。
- 日志和指标记录房间动作。

生产缺口：

- 不包含正式匹配、房间广播、断线恢复、观战和结算。
- 不冻结 `zero-room` 模块或跨服房间路由 API；模块用法见 [房间组件本地契约与扩展边界](reference/room-component-minimum-contract.zh-CN.md)。

### 4.3 scene-sync

适合：

- RPG 场景同步、轻量 MMO 场景、简单 AOI 原型。
- 理解实体进入、移动和可见性查询的最小形态。

生成协议：

```text
SceneSync.si
  enterScene(uid, sceneId, x, y, viewRange, traceId)
  move(uid, sceneId, x, y, traceId)
  queryVisible(uid, sceneId, traceId)
```

运行摘要前缀：

```text
scene-sync=ok
```

框架能力触点：

- Actor lane 按场景维度串行化实体状态。
- 小规模内存 Map 存储实体坐标。
- 使用 Chebyshev 距离演示可见性查询。

生产缺口：

- 不包含正式 AOI 索引、广播、delta 压缩或客户端快照协议。
- 不覆盖跨服迁移、地图切分或复杂场景一致性；模块用法见 [AOI / 状态同步本地契约与扩展边界](reference/aoi-state-sync-minimum-contract.zh-CN.md)。

### 4.4 frame-sync

适合：

- lockstep、帧同步房间、输入收集和固定帧推进原型。
- 理解同一比赛内输入如何串行收集和推进帧。

生成协议：

```text
FrameSync.si
  joinMatch(uid, matchId, traceId)
  submitInput(uid, matchId, frame, input, traceId)
  advanceFrame(matchId, frame, traceId)
  querySnapshot(matchId, traceId)
```

运行摘要前缀：

```text
frame-sync=ok
```

框架能力触点：

- Actor lane 按 match 维度串行化比赛状态。
- 收集固定帧输入并生成快照摘要。
- 日志和指标记录加入、输入、推进和查询动作。

生产缺口：

- 不包含时钟同步、断线补帧、回滚、观战和可靠广播；模块用法见 [帧同步本地契约与扩展边界](reference/frame-sync-minimum-contract.zh-CN.md)。
- 不冻结正式帧协议、反作弊或跨服房间语义。

### 4.5 npc-tick

适合：

- AI NPC 生命周期、zone tick、行为状态机雏形。
- 理解低频 tick 业务如何放在 Actor lane 中串行推进。

生成协议：

```text
NpcTick.si
  spawnNpc(npcId, zoneId, x, y, traceId)
  setBehavior(npcId, zoneId, behavior, traceId)
  tickZone(zoneId, tick, traceId)
  queryNpc(npcId, zoneId, traceId)
```

运行摘要前缀：

```text
npc-tick=ok
```

框架能力触点：

- Actor lane 按 zone 维度串行化 NPC 状态。
- tick 推进会根据当前行为更新位置和动作摘要。
- 日志和指标记录 NPC 生成、行为切换、tick 和查询。

生产缺口：

- 不包含行为树、寻路、战斗 AI、tick 预算或背压策略。
- 不覆盖 NPC 跨服迁移、热更新行为脚本或生产调度观测。
- 模块用法见 [NPC tick 本地契约与扩展边界](reference/npc-tick-minimum-contract.zh-CN.md)。

### 4.6 ranking-season

适合：

- 排行榜、赛季积分、玩家排名查询和赛季切换原型。
- 理解榜单状态如何在本地串行修改并输出查询摘要。

生成协议：

```text
RankingSeason.si
  submitScore(uid, seasonId, score, traceId)
  queryTop(seasonId, limit, traceId)
  queryPlayerRank(uid, seasonId, traceId)
  resetSeason(seasonId, nextSeasonId, traceId)
```

运行摘要前缀：

```text
ranking-season=ok
```

框架能力触点：

- Actor lane 按 season 维度串行化榜单状态。
- 本地内存结构演示积分提交、Top 查询、排名查询和赛季切换。
- 日志和指标记录榜单动作。

生产缺口：

- 不包含 Redis sorted set、跨服榜、结算奖励和幂等补偿。
- 不覆盖容量、热 key、排行榜快照或降级策略。
- 模块用法见 [排行榜 / 赛季本地契约与扩展边界](reference/ranking-season-minimum-contract.zh-CN.md)。

### 4.7 world-shard

适合：

- 开放世界分片、实体迁移和跨 shard 状态查询原型。
- 理解 world / shard 状态边界和迁移摘要。

生成协议：

```text
WorldShard.si
  enterWorld(uid, worldId, shardId, x, y, traceId)
  moveEntity(uid, worldId, x, y, traceId)
  transferShard(uid, worldId, targetShardId, x, y, traceId)
  queryEntity(uid, worldId, traceId)
```

运行摘要前缀：

```text
world-shard=ok
```

框架能力触点：

- Actor lane 按 world 维度串行化实体状态。
- 本地状态保存实体所在 shard 和坐标。
- 迁移操作演示 shard 切换和状态查询。

生产缺口：

- 不包含跨进程迁移、可靠状态交接、跨服广播或 AOI 拼接。
- 不冻结正式 WorldShard、ScenePartition、迁移协议或一致性边界。
- 模块用法见 [开放世界 / 分片迁移本地契约与扩展边界](reference/world-shard-minimum-contract.zh-CN.md)。

## 5. 从模板接入现有模块与生产能力

模板内的业务演示与框架模块是不同产物。Room、AOI/状态同步、帧同步、NPC、榜单、世界分片已有本地最小模块，应先参考对应示例复用；面向生产扩展时仍需以下设计和验证：

| 方向 | 必须补齐 |
| --- | --- |
| 公共 API | 请求 / 响应模型、ErrorCode、兼容策略、版本演进 |
| 线程与 Actor | lane 选择、背压、队列指标、跨 Actor 消息边界 |
| 网络接入 | 握手、鉴权、心跳、重连、限流、连接治理和压测 |
| 数据与缓存 | Repository / Cache 抽象接入、脏数据追踪、落库失败降级 |
| 日志与指标 | 复用 `schemaVersion=1`、`LogAppender`、真实 ErrorCode、有序标签 schema；补齐 production sink、Endpoint 安全接入、容量和告警闭环 |
| 分布式语义 | RPC、服务发现、跨进程路由、幂等和故障演练 |
| 安全与运营 | GM dry-run、审批、RBAC、IP 白名单和审计 |

Room / Matchmaking 方向的现有本地实现与扩展候选见 [房间组件本地契约与扩展边界](reference/room-component-minimum-contract.zh-CN.md)，其中列出生命周期、成员状态、Actor 归属、广播顺序、结算幂等、匹配 ticket 和跨服边界候选。

AOI / State Sync 方向的现有本地实现与扩展候选见 [AOI / 状态同步本地契约与扩展边界](reference/aoi-state-sync-minimum-contract.zh-CN.md)，其中列出 AOI、兴趣管理、实体状态、可见性事件、snapshot / delta、广播背压和跨服迁移边界候选。

Frame Sync 方向的现有本地实现与扩展候选见 [帧同步本地契约与扩展边界](reference/frame-sync-minimum-contract.zh-CN.md)，其中列出固定帧时钟、输入收集、帧推进、snapshot / rollback 边界、广播顺序、观战和反作弊边界候选。

NPC Tick 方向的现有本地实现与扩展候选见 [NPC tick 本地契约与扩展边界](reference/npc-tick-minimum-contract.zh-CN.md)，其中列出 NPC 生命周期、zone tick、行为状态、tick 预算、背压、降级、adapter 超时和热更边界候选。

Ranking / Season 方向的现有本地实现与扩展候选见 [排行榜 / 赛季本地契约与扩展边界](reference/ranking-season-minimum-contract.zh-CN.md)，其中列出排行榜模型、赛季生命周期、积分提交、查询快照、Redis / Cache 边界、结算奖励和幂等补偿候选。

World Shard 方向的现有本地实现与扩展候选见 [开放世界 / 分片迁移本地契约与扩展边界](reference/world-shard-minimum-contract.zh-CN.md)，其中列出世界模型、分片模型、实体归属、迁移状态机、状态交接、跨服路由、AOI 拼接和广播一致性候选。

除七种完整玩法模板外，`templates/config-hot-reload-snippet` 提供可叠加到任意生成项目的 CSV 配置片段，包括 typed 道具 record、独立配置模块、示例 CSV 和 Starter 装配说明。它不会改变 `NewLocalGame` 的七种模板清单；复制后仍需显式配置 `zero.config.hot-reload.enabled=true` 并使用受管非内联 remote IO executor。详细边界见 [CSV 配置加载与本地原子热重载](guides/csv-config-hot-reload.zh-CN.md)。

`templates/managed-scheduler-snippet` 提供另一类可叠加片段：统一注册 once、fixed-delay、fixed-rate 和 Actor deadline 消息，直接返回异步 gateway stage，并在模块关闭时取消周期 handle。它同样不改变七种完整玩法模板清单；复制后必须显式配置 `zero.scheduler.enabled=true`，使用非内联 background executor，并保证 player/scene/entity 状态只在 Actor lane 修改。详细边界见 [本地受管定时任务运行时](guides/managed-scheduler.zh-CN.md)。

`templates/observability-snippet` 提供可叠加的最小日志/指标模块：装配层创建一次 `LogPipeline` 并向业务注入 `LogAppender`，业务不保存或直接调用 terminal `LogSink`；成功记录不携带 ErrorCode，失败记录传入真实 ErrorCode；`MetricDefinition` 显式声明 `module / operation / result` 有序 schema。默认安全门拒绝 token、密码、secret、credential、raw command 和凭据 URI，并脱敏 IP、operator、accountId、playerId、targetId；TraceId 和完整 IP 不进入指标标签。该片段同样不改变七种完整玩法模板清单，完整边界见 [可观测性最小运行时](guides/observability-runtime.zh-CN.md)。

上述三个片段都是 local / single-process / minimum-slice 接入。可观测性片段不提供 production file/Kafka sink、Prometheus HTTP endpoint、容量、长稳或 SLA 保证，`productionReady=false`。

上述内容涉及公共 API、线程模型、协议、存储、权限或模块依赖方向时，不能从模板直接静默演进到生产模块；应先公开设计、评审风险并补齐针对性验证。

## 6. 批量验证

验证所有当前模板：

```powershell
mvn -q -DskipTests install
java scripts/VerifyLocalScaffolds.java --outputDir target\scaffold-verify
```

该命令会生成并验证七种 local/prototype 项目，检查 README `Next Business Step` / `BUSINESS_GUIDE.md` 首改 recipe / `COMPONENTS.md` / `zero-scaffold.json`，通过 `RunLocalScaffold` 执行结构检查、`clean test`、`exec:java` 和摘要校验。它仍然不连接真实中间件，也不证明生产就绪；正式模块推进前应提交 GitHub Design Proposal 并完成维护者评审。

## 7. 按需组装接入闭环

| 场景 | 入口 | 验证范围 |
| --- | --- | --- |
| 最小 runtime 与自选组件 | `NewLocalGame --template runtime --components ...`，随后直接运行 Maven | 生成依赖、组装、诊断、运行 |
| 七类默认业务原型 | `RunLocalPrototype` 或 `RunLocalScaffold` | 协议生成、业务首改流程与本地 smoke |
| 业务原型结构检查 | `InspectLocalScaffold` | 七类业务模板的协议和文档约定，不适用于纯 runtime |
| 全部公开组件选择 | `VerifyGeneratedCompositions` | 每项选择真实编译运行，代表路径检查最小 classpath，混合选择检查 provider/capability |
| 真实 Repository 实现 | `VerifyRepositoryDrivers.ps1` | 本轮专用数据库中的同一业务契约 |

从仓库根目录执行：

```powershell
mvn -B -ntp -q -DskipTests install
java scripts/NewLocalGame.java --template runtime --components event,custom-actor --projectName my-runtime --packageName group.example.runtime --outputDir target/my-runtime
mvn -B -ntp -q -f target/my-runtime/pom.xml clean test
mvn -B -ntp -q -f target/my-runtime/pom.xml exec:java '-Dexec.args=--diagnose'
mvn -B -ntp -q -f target/my-runtime/pom.xml exec:java
```

在生成的 `RuntimeAssembly.java` 中替换 provider；业务服务继续依赖能力接口。`custom-actor` 给出显式注册和 override 示例。选择 `data` 或 `redis` 时，生成的 Repository 角色 `main` 分别绑定 `local` 或 `redis`；自定义角色与 MongoDB/PostgreSQL 接入见 [Repository 指南](guides/repository-composition-guide.zh-CN.md)。

`--diagnose` 与 `--start` 分开执行。外部 runtime 默认只诊断配置，不创建客户端，需根据 `config/application.properties.example` 设置 `ZERO_CONFIG_FILE`，检查外部诊断报告的 `missingConfigKeys`，再执行 `mvn ... exec:java '-Dexec.args=--start'`。诊断成功不表示服务可达。

脚手架在写入文件前拒绝未知参数、重复参数（包括别名重复）、非法组件、Java 关键字包名及不能形成合法类名的项目名。例如 `123-game` 被拒绝，`game-123` 可生成 `Game123Application`。`--components` 必须准确拼写；不会将拼错的参数静默变成默认选择。

`VerifyGeneratedCompositions` 从代码生成器组件目录读取选择集合，不单独维护另一份组件目录。当前包含五条代表消费路径、十六个单组件选择、两条混合路径及中心—逻辑/分布式组合，共二十五个生成消费者。清单/POM 的结构检查使用 JSON/XML 解析，runtime 模板测试再对照实际 runtime plan 检查所选 provider 与 capability。
