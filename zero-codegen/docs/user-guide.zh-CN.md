# zero-codegen 用户指南

本文面向客户端开发、活动策划和工具链同学，说明如何用 `zero-codegen` 从 `.si` 协议工程生成 Java、C#、TypeScript 和 GDScript 协议代码。

## 1. 你会用到什么

`zero-codegen` 负责把协议声明转成可直接接入项目的代码，包含：

- Java 服务端 DTO、payload codec、协议号、BO 接口、BO 默认实现模板、分发器。
- C#、TypeScript、GDScript 客户端协议代码。
- 标准 DSL 文档和示例。
- GUI 工具模式和可独立打包的 fat jar。

## 2. 适合谁

- 客户端开发：需要同步获取协议 DTO、枚举和 codec。
- 活动策划：需要修改协议示例、字段或事件定义后快速重新生成。
- 后端开发：需要把协议变更同步到服务端事件接口和分发器。

## 3. 标准工作流

```text
编写/修改 .si
  -> 更新 protoId.txt
  -> 选择目标语言
  -> 生成代码
  -> 联调读写与事件处理
  -> 生成测试样例
```

## 4. 推荐目录

建议把协议工程按业务域拆开：

```text
protocol/
  protoId.txt
  auth/Auth.si
  role/Role.si
  player/Player.si
```

其中：

- `Auth.si` 负责登录、账号和会话初始化。
- `Role.si` 负责角色列表、创建角色和选角。
- `Player.si` 负责玩家快照、属性、背包和心跳。

## 5. 一个完整示例

标准流程示例已放在：

```text
zero-codegen/src/test/resources/protocol-dsl/standard-flow/
```

它覆盖了一个完整最小闭环：

1. 登录。
2. 获取角色列表。
3. 创建角色。
4. 选角色进入游戏。
5. 拉取玩家快照。
6. 心跳和玩家状态更新。

### 5.1 协议示例

`Auth.si`：

- `login(...)`
- `loginResult(...)`

`Role.si`：

- `listRoles(...)`
- `createRole(...)`
- `selectRole(...)`

`Player.si`：

- `queryPlayer(...)`
- `heartbeat(...)`
- `updatePlayerStat(...)`
- `playerSnapshot(...)`
- `heartbeatAck(...)`

### 5.2 生成命令

CLI 示例：

```powershell
java -jar zero-codegen/target/zero-codegen-0.1.0-SNAPSHOT-all.jar `
  --input zero-codegen/src/test/resources/protocol-dsl/standard-flow `
  --protoId zero-codegen/src/test/resources/protocol-dsl/standard-flow/protoId.txt `
  --out target/generated-sources/zero-codegen `
  --pkg group.zn.zero.standard `
  --languages java,csharp,typescript,gdscript `
  --genBoImpl true
```

默认生成的协议消息类型会追加 `DTO` 后缀，例如 `AuthLoginProtocolDTO`。如果某个目标语言需要自己的命名习惯，可以追加：

```powershell
  --dtoSuffix DTO `
  --javaDtoSuffix "" `
  --csDtoSuffix Dto
```

其中 `--dtoSuffix` 是通用默认值，各语言参数会单独覆盖；空字符串表示该语言不追加后缀。

GUI 示例：

```powershell
java -jar zero-codegen/target/zero-codegen-0.1.0-SNAPSHOT-all.jar --gui
```

Windows 客户端也可以复制并修改一键 CLI 脚本：

```text
zero-codegen/scripts/zero-codegen-cli.bat
```

脚本里已经把 Java 启动命令、输入目录、`protoId.txt`、输出目录、包名、目标语言和 Java 生成物布局做成变量，适合作为项目内工具入口模板；默认优先使用 `JAVA_HOME\bin\java.exe`。

## 6. 目标语言怎么选

- `java`：服务端协议 DTO、codec、BO 和分发器。
- `csharp`：Unity 或 .NET 客户端。
- `typescript`：Web、H5 或工具页。
- `gdscript`：Godot 客户端。

如果只做客户端联调，可以只勾选客户端语言。

## 7. 常见产物

Java：

- `dto/ZeroGeneratedPayload.java`
- `dto/XXXProtocolDTO.java`
- `dto/codec/XXXProtocolDTOCodec.java`
- `protocol/ProtocolIds.java`
- `protocol/GeneratedProtocolDefinitions.java`
- `bo/XXXEventBO.java`
- `bo/impl/XXXEventBOImp.java`
- `protocol/dispatch/GeneratedProtocolDispatcher.java`

客户端：

- `ZeroProtocolRuntime.*`
- `ProtocolIds.*`
- `*DTO.cs`
- `*DTOCodec.cs`
- `*DTO.ts`
- `*DTOCodec.ts`
- `zero_protocol.gd`

## 8. 常见问题

### 8.1 我改了 `.si`，但生成没变化

先确认：

- 是否改的是当前输入目录。
- 是否重新执行了生成命令。
- 是否 `protoId.txt` 与 `.si` 文件名一致。

### 8.2 为什么 `Set` / `Map` 看起来没有固定顺序

这是协议线格式的默认策略。需要跨端稳定字节序时，应在业务层显式排序后再写入。

### 8.3 为什么 `optional` 和 `nullable` 会生成 `presence bitmap`

这是为了区分“字段不存在”和“字段有值但为空”，避免和普通空集合混淆。

### 8.4 为什么有些类型不能直接生成

当前只支持协议标准里明确约定的类型。若需要新类型，先评估它会不会影响跨端线格式，再扩展 DSL。

### 8.5 GDScript 为什么是单文件

为了降低 Godot 接入成本，当前先采用单文件 runtime + codec 方案，便于直接复制或挂载到项目里。

### 8.6 客户端只想生成一部分语言怎么办

在 CLI 或 GUI 中只勾选需要的语言即可，生成器会按语言分目录输出。

### 8.7 为什么 DTO 默认带 `DTO` 后缀

这是为了和后续可能生成的持久化类、缓存类或业务对象区分。每种目标语言都可以单独配置后缀；同时生成的 DTO 只带轻量 marker，不依赖 `ZeroPayloadCodec`，codec 仍然是独立的伴生类。

### 8.8 服务端想把 DTO、BO、分发器放到不同模块怎么办

默认 Java 布局会从 `--pkg` 派生：DTO 在 `<pkg>.dto`，codec 在 `<pkg>.dto.codec`，协议号在 `<pkg>.protocol`，BO 在 `<pkg>.bo`，分发器在 `<pkg>.protocol.dispatch`。如果项目拆分为 proto、logic、net/dispatch 等模块，可以使用：

```powershell
  --outJavaDto path/to/proto `
  --javaDtoPkg group.zn.zero.proto.dto `
  --outJavaBo path/to/logic `
  --javaBoPkg group.zn.zero.logic.bo `
  --outJavaProtocol path/to/net `
  --javaProtocolPkg group.zn.zero.net.protocol `
  --outJavaDispatcher path/to/net `
  --javaDispatcherPkg group.zn.zero.net.dispatch
```

未填写的生成物会按相邻默认项派生，例如 codec 跟随 DTO，BOImp 跟随 BO，dispatcher 跟随 protocol。

## 9. 策划怎么用

如果你只需要活动协议或配置同步：

1. 找到对应 `.si`。
2. 只改枚举、结构体和方法参数。
3. 重新生成客户端代码。
4. 把变更后的协议文件交给客户端和后端一起联调。

你不需要手写读写逻辑，只需要维护协议声明。

## 10. 更新工具与接入当前运行时

在仓库根目录使用 Java 21 执行 `mvn -pl zero-codegen -am package`，更新完整 CLI/GUI JAR；然后用项目原有的输入、包名和输出目录参数重新生成。GUI 与 CLI 共用同一个生成后端，无需切换优化开关。

- 网络 handler 优先调用 `dispatcher.dispatchFrame(frame)`，直接读取帧持有的 payload；BO 仍接收自持有 DTO，不需要释放缓冲。
- `dispatch(protocolId, payload)` 仍可用，调用期间不要修改数组。两种入口均拒绝对象外尾随数据，且不会在解码失败时调用业务 BO；对象内部追加字段仍按既有长度边界跳过。
- BO 在启动时注册并安全发布；注册不得与分发并发，业务线程约束仍由调用方保证。
- `--genBoImpl true` 只创建不存在的 BOImp，已有实现即使保留旧生成标记也不会覆盖。接口变化需要手工更新业务实现。
- DTO、codec、协议号、BO 接口和 dispatcher 等工具管理文件内容相同时不重写；非生成文件仍拒绝覆盖。请勿手工修改工具管理文件。
- 普通协议生成不提供脚手架的 ownership hash、事务或回滚保证，也不清理旧包名/旧后缀下的产物；修改布局后需检查过期文件。

跨模块布局应让业务 dispatcher 依赖 BO 与协议模块，不能让框架 `zero-net` 反向依赖业务代码。迁移和验证范围见 [codegen 对接迁移](../../docs/migrations/20260923-codegen-integration.md)。

## 11. 已知边界

- 生成器不替你设计业务流程。
- 复杂排序策略、签名、加密、压缩和权限字段，应由业务层或后续扩展头承接。
- 当前 GDScript 以可用和易接入为主，实际 Godot 端编译仍建议结合项目验证。
