# zero-codegen 协议 DSL 标准

本文档描述 `zero-codegen` 在 S1-05 阶段支持的标准 `.si` 协议 DSL。`.si` 是当前推荐入口，旧块式 DSL 暂时保留用于兼容测试和历史生成链路。

## 1. 设计目标

- 面向 zeroServer 自研二进制协议生成 DTO、协议号、业务接口和默认实现模板。
- 协议标准只描述通用结构、类型和方向，不绑定登录、RPC、TraceId、鉴权、房间或场景同步等业务字段。
- 业务方法以协议事件为单位生成 `XXXEventBO` 和可选 `XXXEventBOImp`，便于业务开发者按单个协议入口实现。
- 类型模型优先支持高频协议场景：标量、enum、struct、List、Set、Map、数组、多维数组、嵌套泛型和 nullable。

## 2. 文件组织

工具入口支持输入单个 `.si` 文件，也支持输入目录。目录会递归扫描全部 `.si` 文件，并按绝对路径排序后生成，保证同一目录结构下输出稳定。

schema 名称来自文件名，不包含扩展名。例如：

```text
protocol/
  protoId.txt
  game/Player.si
  item/Inventory.si
```

`Player.si` 会按方法生成 `PlayerQueryPlayerEventBO` / `PlayerQueryPlayerEventBOImp`，
`Inventory.si` 会按方法生成 `InventoryQueryInventoryEventBO` / `InventoryQueryInventoryEventBOImp`。

## 3. 顶层语法

一个 `.si` 文件可以包含以下顶层块：

```si
enum PlayerRole {
  UNKNOWN,
  WARRIOR,
  MAGE
}

struct PlayerInfo {
  long uid;
  String name;
  int level;
  PlayerRole role;
}

client_to_server:
  queryPlayer(long uid, String traceId);

server_to_client:
  queryPlayerResult(PlayerInfo player);
```

支持：

- `//` 行尾注释。
- `#` 整行注释。
- 空行。
- 行尾 `;` 或 `,`。
- 全角冒号会归一化为半角冒号。

当前不支持：

- `import/include`。
- 块注释。
- 文件内 namespace。
- 宏系统。

## 4. 类型系统

标量类型：

- `boolean` / `bool`
- `byte`
- `short`
- `int` / `Integer`
- `uint`
- `long`
- `ulong`
- `id`
- `count`
- `float`
- `double`
- `String` / `string`
- `bytes`

复合类型：

- enum 引用：`PlayerRole`
- struct 引用：`PlayerInfo`
- 列表：`List<T>`、`ArrayList<T>`、`LinkedList<T>`、`Collection<T>`
- 集合：`Set<T>`、`HashSet<T>`、`LinkedHashSet<T>`
- 映射：`Map<K,V>`、`HashMap<K,V>`、`LinkedHashMap<K,V>`、`Dictionary<K,V>`
- 数组：`T[]`
- 多维数组：`int[][]`、`PlayerInfo[][]`
- 嵌套泛型：`List<Map<String,Integer>>`、`List<PlayerItem>[]`
- nullable：`Optional<T>`、`optional<T>`、`nullable<T>`、`T?`

示例：

```si
struct CollectionPayload {
  List<Map<String,Integer>> counters;
  int[][] matrix;
  List<PlayerItem>[] buckets;
  Optional<String> title;
}
```

字段按声明顺序自动分配 order，从 `1` 开始。nullable 字段会在模型中标记为可空，具体线格式由 `zero-protocol` 的 presence bitmap 和 sizePlusOne 规则承接。

## 5. enum

enum 值可以省略数字，默认从 `0` 递增；也可以显式赋值。

```si
enum ErrorLevel {
  NONE = 0,
  WARN = 1,
  FATAL = 10
}
```

enum 名称、值名称和值都必须在同一个 enum 内唯一。

## 6. struct

struct 是协议 DTO 的主要来源。

```si
struct PlayerItem {
  long itemId;
  int count;
  Set<String> flags;
}
```

生成 Java DTO 时，当前阶段采用可读性优先的 `public field` 结构，并为集合、Map、数组和字符串提供保守默认值。后续生成 codec 时，字段顺序必须与 `.si` 声明顺序一致。

## 7. 方法区

方法区按方向分为两类：

```si
client_to_server:
  queryPlayer(long uid, String traceId);

server_to_client:
  queryPlayerResult(PlayerInfo player);
```

每个方法会自动生成一个消息 DTO：

```text
<Schema><Method>ProtocolDTO
```

默认生成物会在协议消息类型名后追加 `DTO` 后缀，例如 `Player.si` 中的 `queryPlayer` 会生成
`PlayerQueryPlayerProtocolDTO`。后缀可按目标语言单独配置；需要生成持久化类等无后缀类型时，可把对应语言后缀设为空字符串。
BO 方法默认接收协议 DTO：

```java
void queryPlayer(PlayerQueryPlayerProtocolDTO request);
void queryPlayerResult(PlayerQueryPlayerResultProtocolDTO request);
```

当前阶段为了保持 `zero-codegen` 不依赖 Netty，BO 方法不内置 `Channel` 参数。网络连接、session、TraceId、玩家上下文等业务运行时信息应由后续事件/网络适配层封装。

## 8. protoId 文件

生产协议建议显式维护 `protoId.txt`。格式：

```text
Player 1000 2000
Inventory 3000 4000
```

每行三列：

- schema 基名。
- client-to-server 起始 ID。
- server-to-client 起始 ID。

分配规则：

- `client_to_server` ID 会规整到奇数起点，然后每个方法递增 `2`。
- `server_to_client` ID 会规整到偶数起点，然后每个方法递增 `2`。
- 同一个工程内协议 ID 和协议名称必须唯一。
- `protoId.txt` 中声明的 schema 必须能找到同名 `.si` 文件，否则解析失败。

## 9. CLI

工具入口：

```text
group.zn.zero.codegen.ProtocolCodegenCli
```

参数：

| 参数 | 说明 | 默认值 |
| --- | --- | --- |
| `--input` | `.si` 文件或目录，多个路径用逗号分隔 | 必填 |
| `--out` | 默认输出根目录；Java 默认直接输出到该目录，客户端默认输出到语言子目录 | `target/generated-sources/zero-codegen` |
| `--pkg` | Java 协议包名 | `group.zn.zero.generated` |
| `--protoId` | protoId 文件路径 | 空 |
| `--languages` | 目标语言列表，支持 `java,csharp,typescript,gdscript` | `java` |
| `--genJava` | 是否生成 Java 代码 | 跟随 `--languages` |
| `--genCs` | 是否生成 C# 代码 | 跟随 `--languages` |
| `--genTs` | 是否生成 TypeScript 代码 | 跟随 `--languages` |
| `--genGd` | 是否生成 GDScript 代码 | 跟随 `--languages` |
| `--outJava` | Java 输出目录 | 等同 `--out` |
| `--outJavaDto` | Java DTO、enum、marker 输出目录 | 跟随 `--outJava` |
| `--outJavaCodec` | Java payload codec 输出目录 | 跟随 `--outJavaDto` |
| `--outJavaProtocol` | Java `ProtocolIds` 与协议定义输出目录 | 跟随 `--outJava` |
| `--outJavaBo` | Java BO 接口输出目录 | 跟随 `--outJava` |
| `--outJavaBoImpl` | Java BO 默认实现输出目录 | 跟随 `--outJavaBo` |
| `--outJavaDispatcher` | Java 协议分发器输出目录 | 跟随 `--outJavaProtocol` |
| `--outCs` | C# 输出目录 | `<out>/csharp` |
| `--outTs` | TypeScript 输出目录 | `<out>/typescript` |
| `--outGd` | GDScript 输出目录 | `<out>/gdscript` |
| `--dtoSuffix` | 四种目标语言共用的 DTO 后缀 | `DTO` |
| `--javaDtoSuffix` | Java DTO 后缀；传空字符串可关闭 | 跟随 `--dtoSuffix` |
| `--csDtoSuffix` | C# DTO 后缀；传空字符串可关闭 | 跟随 `--dtoSuffix` |
| `--tsDtoSuffix` | TypeScript DTO 后缀；传空字符串可关闭 | 跟随 `--dtoSuffix` |
| `--gdDtoSuffix` | GDScript DTO 后缀；传空字符串可关闭 | 跟随 `--dtoSuffix` |
| `--javaDtoPkg` | Java DTO、enum、marker 包名 | `<pkg>.dto` |
| `--javaCodecPkg` | Java payload codec 包名 | `<javaDtoPkg>.codec` |
| `--javaProtocolPkg` | Java `ProtocolIds` 与协议定义包名 | `<pkg>.protocol` |
| `--javaBoPkg` | Java BO 接口包名 | `<pkg>.bo` |
| `--javaBoImplPkg` | Java BO 默认实现包名 | `<javaBoPkg>.impl` |
| `--javaDispatcherPkg` | Java 协议分发器包名 | `<javaProtocolPkg>.dispatch` |
| `--csNs` | C# 命名空间 | 由 Java 包名转 PascalCase |
| `--tsNs` | TypeScript 命名空间标记 | Java 包名 |
| `--gdNs` | GDScript 命名空间标记 | Java 包名 |
| `--genBoImpl` | 是否生成 BO 默认实现模板 | `false` |
| `--gui` | 启动 Swing 图形工具 | 空 |
| `--help` | 输出命令行帮助 | 空 |

示例：

```powershell
java -cp target/classes group.zn.zero.codegen.ProtocolCodegenCli `
  --input zero-codegen/src/test/resources/protocol-dsl/sample `
  --protoId zero-codegen/src/test/resources/protocol-dsl/sample/protoId.txt `
  --out target/generated-sources/zero-codegen `
  --pkg group.zn.zero.generated `
  --languages java,csharp,typescript,gdscript `
  --genBoImpl true
```

## 10. GUI 工具模式

`zero-codegen` 保留命令行入口，同时新增 Swing GUI 模式，方便策划、客户端或工具链同学在不打开服务端工程的情况下生成协议代码。

启动方式：

```powershell
java -jar target/zero-codegen-0.1.0-SNAPSHOT-all.jar --gui
```

无参数运行可在有图形桌面的环境中自动打开 GUI；无图形环境下会输出 CLI 帮助并返回失败码。GUI 和 CLI 共用 `ProtocolCodegenRunner`，因此解析、校验、FreeMarker 渲染和输出覆盖保护保持一致。

GUI 当前提供：

- 添加 `.si` 文件或目录。
- 选择 `protoId.txt`。
- 选择输出目录。
- 设置 Java 包名。
- 勾选 Java、C#、TypeScript、GDScript 目标语言。
- 分别设置 C#、TypeScript、GDScript 输出目录与命名空间标记。
- 分别设置 Java、C#、TypeScript、GDScript DTO 后缀；默认 `DTO`，留空表示该语言不追加后缀。
- 分别设置 Java DTO、codec、protocol、BO、BOImp、dispatcher 的输出目录和包名；留空时按 Java 基础输出目录与基础包名派生。
- 勾选是否生成 `XXXEventBOImp`。
- 查看生成日志和错误码。

面向客户端和活动策划的完整使用流程与常见问题见：

```text
zero-codegen/docs/user-guide.zh-CN.md
```

## 11. 独立打包

`zero-codegen` 在 Maven `package` 阶段通过 `maven-shade-plugin` 附加生成可执行 fat jar：

```powershell
mvn -pl zero-codegen -am package
java -jar zero-codegen/target/zero-codegen-0.1.0-SNAPSHOT-all.jar --help
```

该 jar 内含 FreeMarker 模板、DSL 标准示例资源和依赖，可用于 CLI 或 GUI。需要进一步生成 Windows/macOS/Linux 可执行文件时，可基于 JDK 21 的 `jpackage` 包装该 jar，例如：

Windows 下可以直接复制并修改一键 CLI 脚本：

```text
zero-codegen/scripts/zero-codegen-cli.bat
```

脚本内预留了 Java 启动命令、输入目录、`protoId.txt`、输出目录、目标语言、DTO 后缀和 Java 生成物布局变量，客户端项目可以按自身目录结构调整后双击运行；默认优先使用 `JAVA_HOME\bin\java.exe`。

```powershell
jpackage `
  --type exe `
  --name zero-codegen `
  --input zero-codegen/target `
  --main-jar zero-codegen-0.1.0-SNAPSHOT-all.jar `
  --main-class group.zn.zero.codegen.ProtocolCodegenCli `
  --dest zero-codegen/target/dist
```

## 12. 生成物

当前 Java 生成物包括：

- enum：`dto/PlayerRole.java`
- struct DTO：`dto/PlayerInfoDTO.java`
- 方法 DTO：`dto/PlayerQueryPlayerProtocolDTO.java`
- 轻量 marker：`dto/ZeroGeneratedPayload.java`
- payload codec：`dto/codec/PlayerQueryPlayerProtocolDTOCodec.java`
- 协议号：`protocol/ProtocolIds.java`
- 协议注册定义：`protocol/GeneratedProtocolDefinitions.java`
- BO 接口：`bo/PlayerQueryPlayerEventBO.java`
- BO 默认实现：`bo/impl/PlayerQueryPlayerEventBOImp.java`
- 协议分发器：`protocol/dispatch/GeneratedProtocolDispatcher.java`

默认包名与目录保持同一基础前缀：DTO、enum、marker 位于 `<pkg>.dto`，codec 位于 `<pkg>.dto.codec`，协议号与协议定义位于 `<pkg>.protocol`，BO 位于 `<pkg>.bo`，BOImp 位于 `<pkg>.bo.impl`，分发器位于 `<pkg>.protocol.dispatch`。如果服务端项目拆分为 proto、logic、net/dispatch 等模块，可以通过 CLI 或 GUI 对这些生成物逐项覆盖输出目录和包名。

Java 生成侧使用 `zero-codegen/src/main/resources/codegen/java` 下的 FreeMarker 模板渲染，复杂读写表达式在渲染前由 Java 模型预处理，避免模板内堆积协议语义。

随模板打包的 DSL 标准格式与示例位于：

```text
zero-codegen/src/main/resources/codegen/dsl/standard-format.zh-CN.md
zero-codegen/src/main/resources/codegen/dsl/standard-example.si
zero-codegen/src/main/resources/codegen/dsl/protoId-example.txt
```

C#、TypeScript 和 GDScript 后端当前已经接入同一份类型树、协议号分配结果和 `ProtocolCodegenRunner` 入口。默认按语言分目录输出，客户端生成物包含轻量运行时、协议号、枚举、带语言可配置后缀的 DTO、轻量 marker 和 payload codec。

## 13. 兼容与演进规则

- 字段只能尾部追加。
- 不允许删除字段。
- 不允许重排字段。
- 不允许改变既有字段类型。
- 协议 ID 不得复用。
- `Set` / `Map` 默认不承诺跨语言稳定迭代顺序；需要稳定字节序时，应在业务层或后续 DSL 扩展中显式声明排序策略。
- 业务场景字段不进入核心 proto 标准，可通过事件模型、网络适配层、RPC 模型或 frame extension 承接。

## 14. 测试示例

标准测试示例位于：

```text
zero-codegen/src/test/resources/protocol-dsl/sample/
  protoId.txt
  game/Player.si
  common/Inventory.si
```

该示例覆盖目录递归、enum、struct、方法 DTO、`protoId`、List、Set、Map、数组、多维数组、嵌套泛型和 nullable。
