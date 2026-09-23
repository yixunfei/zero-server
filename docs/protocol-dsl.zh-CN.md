# 协议 DSL 与代码生成设计


2026-09-23 Java 生成分发新增 `dispatchFrame(ProtocolFrame)`：从自持有 payload 直接构建只读 reader，BO 仍接收原有 DTO/业务参数。已有数组 dispatch 同样使用只读 reader；两种入口均在调用 BO 前拒绝对象外尾随数据。自定义 `ProtocolCodec` 的 `decodeView` 默认委托数组解码。用原协议生成命令更新工具管理的源码；已有 BOImp 始终保留，内容相同的各语言产物不重写。见[性能迁移](migrations/20260923-performance-incremental.md)与[codegen 对接迁移](migrations/20260923-codegen-integration.md)。

## 1. 总体目标

zeroServer 使用自定义协议 DSL 声明协议消息、协议方法和业务事件，并通过工具生成服务端与客户端代码。

S1-04 先固化运行时边界：协议定义、协议方向、协议注册、通用二进制 reader/writer、可选通信 frame、payload codec SPI 和 ID 冲突约束。S1-05 再在此基础上实现 DSL 解析、协议号分配、DTO、BO、BOImp、测试模板和多语言代码生成。

设计取舍：

- 优先参考 `L:\zero-codegen` 的 DSL/codegen-first 思路，因为它更贴近 zeroServer 的“协议声明驱动业务开发”目标。
- 借鉴 `L:\zfoo-main\protocol` 的对象长度前缀兼容机制，用于跳过未知尾部字段。
- 不直接照搬任一项目实现，不把 Netty、反射增强或具体业务场景格式绑定到 `zero-protocol`。

## 2. Zero Binary Protocol v1

核心 proto 是通用二进制序列化格式，不等同于网络包格式。通信场景可以在 payload 外套一层 frame；数据序列化、缓存、压缩前编码和持久化中间态可以直接使用 payload 编码。

标量编码：

- `boolean`：1 字节，`0` 为 false，非 0 为 true。
- `byte`：1 字节原样写入。
- `short`：按 signed `int` 编码。
- `int`：ZigZag + VarInt，支持完整 `int` 范围。
- `long`：ZigZag + VarLong，支持完整 `long` 范围。
- 非负 ID、长度、数量、版本：Unsigned VarInt/VarLong，当前 Java API 约束在非负 `int`/`long` 范围内。
- `float`：固定 4 字节大端。
- `double`：固定 8 字节大端。
- `string`：Unsigned VarInt 字节长度 + UTF-8 字节。
- `bytes`：Unsigned VarInt 字节长度 + 原始字节。

集合与数组编码：

- `List<T>`：`size: unsigned varint` + 按顺序写入的元素。
- `Set<T>`：`size: unsigned varint` + 按迭代顺序写入的元素；默认不承诺跨语言稳定字节序。
- `Map<K,V>`：`size: unsigned varint` + 按迭代顺序写入的 key/value pair；默认不承诺跨语言稳定字节序。
- `T[]`：`size: unsigned varint` + 按下标顺序写入的元素。
- `int[]`、`long[]`、`double[]` 等原始数组由生成代码走专用循环，避免装箱。
- 多维数组按嵌套数组处理，例如 `int[][]` 等价于 `array<array<int>>`。
- 嵌套泛型按类型树递归展开，例如 `List<Map<int,string>>`、`List<int[]>[]`。

nullable 编码：

- required 字段默认不允许 null。
- `optional<T>` / `nullable T` 由 DSL 显式声明。
- nullable collection/array/string/bytes 使用 `sizePlusOne: unsigned varint`：`0 = null`，`1 = empty`，`n = size + 1`。
- 对象内多个 nullable 字段优先使用 presence bitmap：先写字段数量，再按字节打包布尔位，低位对应较小字段序号。
- 集合元素如需 null，应声明为 `List<optional<T>>` 或等价类型，由生成器写元素 presence bitmap。

对象编码：

```text
object
  -> bodyLength: unsigned varint
  -> fields: 按 DSL 声明顺序连续写入
```

对象长度前缀用于兼容尾部追加字段。旧读者读取已知字段后调用 `endObject`，可以跳过对象边界内的未知尾部字节。

### 2.1 运行时缓冲区策略

`ZeroReader` 与 `ZeroWriter` 通过 `ZeroBuffer` 抽象管理底层存储，默认实现是堆内 `byte[]`。这条路径最简单、最稳定，也最适合作为生成代码的默认热路径。

可选实现包括：

- `HeapZeroBuffer`：默认实现，适合大多数协议编解码场景。
- `DirectZeroBuffer`：基于 `DirectByteBuffer`，适合和 IO / 文件通道直接衔接的场景。
- `NativeMemoryZeroBuffer`：基于 Unsafe/native memory，作为后续极限性能实验基础。
- `ZeroBufferSlice`：借用切片，适合大 bytes/payload 的读取场景，避免无意义复制。

默认策略仍然是 heap；direct/native 需要由调用方显式选择，不会自动替换默认行为。
当前轻量基准在 request 式写入、物化输出、读回链路下显示 heap `byte[]` 最稳；
direct/native 后续主要面向文件通道、压缩器、网络 IO 可直接消费 `ByteBuffer`
或 off-heap 内存的场景复测。

## 3. 兼容策略

顺序布局是高性能路径，兼容规则必须保持保守：

- 允许只在对象尾部追加字段。
- 不允许删除字段。
- 不允许重排字段。
- 不允许改变既有字段类型。
- 不允许把业务语义塞进核心线格式。

需要更复杂的兼容策略时，应由业务层或后续 DSL 版本迁移工具处理，例如双写、字段保留、显式迁移或协议版本分流。

## 4. 通信 Frame

通用 frame 只封装通信所需的最小公共字段，不解释 RPC、登录、房间、场景同步、审计等业务语义。

```text
frame
  -> magic: fixed 4 bytes, "ZRO1"
  -> frameVersion: unsigned varint
  -> protocolVersion: unsigned varint
  -> flags: unsigned varint
  -> protocolId: unsigned varint
  -> extensionLength + extension
  -> payloadLength + payload
```

通用 flags：

- `COMPRESSED`：payload 已压缩。
- `ENCRYPTED`：payload 已加密。
- `SIGNED`：payload 或扩展头已签名。
- `EXTENSION_HEADER`：frame 携带扩展头。

扩展头是预留能力，核心协议不定义其内部结构。RPC、TraceId、correlationId、timeoutAt、玩家会话、场景同步等场景字段应在业务层或对应模块封装，避免污染通用序列化标准。

## 5. 运行时模型

`zero-protocol` 当前提供：

- `ProtocolDefinition`：协议 ID、名称、方向、版本、codec 名称和通用特性。
- `ProtocolDirection`：客户端到服务端、服务端到客户端、服务器间调用。
- `ProtocolFeature`：通用特性位。
- `ProtocolFrame`：通用通信帧模型。
- `ZeroWriter` / `ZeroReader`：面向生成代码的二进制 reader/writer，默认使用 heap buffer。
- `ZeroBuffer` / `ZeroBufferSlice` / `ZeroBuffers`：buffer 抽象、借用切片和工厂入口。
- `HeapZeroBuffer` / `DirectZeroBuffer` / `NativeMemoryZeroBuffer`：buffer 的基础实现。
- `ProtocolCodec<T>`：协议消息编解码 SPI。
- `ZeroPayloadCodec<T>`：生成代码适配的 payload codec SPI。
- `GeneratedProtocolCodec<T>`：把生成式 payload codec 适配为运行时 codec。
- `ProtocolFrameCodec` / `ZeroBinaryFrameCodec`：通用 frame 编解码。
- `ProtocolRegistry` / `InMemoryProtocolRegistry`：协议注册和 ID/名称冲突检测。

`zero-protocol` 只依赖 `zero-core`，不依赖 Netty、Kafka、MongoDB、Redis、Nacos 或业务模块。

## 6. DSL 声明字段

协议消息至少声明：

- 消息名称。
- 字段列表。
- 字段顺序。
- 字段类型。
- 字段注释。

字段类型支持：

- 标量：`bool`、`byte`、`short`、`int`、`long`、`float`、`double`、`string`、`bytes`。
- 非负标量约束：`uint`、`ulong`、`id`、`count`，用于 ID、数量和长度等天然非负值。
- 消息引用：`PlayerInfo`。
- 枚举引用：`ItemType`。
- 列表：`List<T>`。
- 集合：`Set<T>`。
- 映射：`Map<K,V>`，第一阶段建议 key 使用标量、string 或 enum。
- 数组：`T[]`，多维数组通过嵌套数组表达。
- 可选：`optional<T>` 或 `nullable T`。
- null 标记：只用于类型系统和生成器内部，不作为普通字段类型直接暴露给业务。

协议方法至少声明：

- 唯一方法名。
- 协议 ID 或 ID 区间。
- 协议方向。
- 请求消息。
- 响应消息，可为空。
- 业务接口名称。

可选声明：

- 版本号。
- 压缩、加密、签名等通用特性。
- 是否生成 BO 默认实现模板。
- 目标语言。
- 错误码模板分组。

业务级权限、运行线程域、幂等键、超时、审计字段、防重放、TraceId 等不进入核心 proto 线格式；后续可以由业务 DSL 或具体模块在扩展头、事件模型、RPC 模型中声明。

## 7. 生成内容

协议工具需要生成：

- Java 协议 DTO。
- Java payload codec。
- Java 协议注册代码。
- Java 事件接口 `XXXEventBO`。
- Java 默认实现模板 `XXXEventBOImp`。
- Java 测试模板。
- ErrorCode 模板。
- 协议文档。
- C# 客户端代码。
- TypeScript 客户端代码。
- GDScript 客户端代码。

生成代码要求：

- 热路径避免反射。
- 字段读写顺序与 DSL 声明顺序一致。
- 对象体使用长度前缀，读端必须在对象边界内读取。
- 生成代码应可读，便于业务开发者定位字段和方法。
- 可在后续为 Netty ByteBuf、池化 byte[]、零拷贝字符串或固定布局数组提供 adapter。
- C# 端针对具体嵌套类型生成专用静态读写方法，避免运行时反射、LINQ 和委托热路径。
- `List<T>`、`Dictionary<K,V>` 和 `HashSet<T>` 生成时应按 size 预分配容量。
- primitive array 使用原生数组精确分配；Java/C# 均避免装箱。
- `Set` 和 `Map` 如果需要跨端稳定字节序，必须由 DSL 或生成参数显式声明排序策略。

后续可扩展目标语言：

- Rust。
- 更多引擎脚本语言或原生客户端语言。

## 8. 协议号策略

协议 ID 是长期兼容契约，应纳入版本控制。S1-05 可以继续沿用独立 `protoId.txt` 或演进为 zeroServer 专用协议号文件，但必须满足：

- 人工声明 ID 区间。
- 工具自动分配具体 ID。
- 工具校验 ID 冲突。
- 工具稳定保存已分配 ID。
- 删除或重命名协议必须保留迁移记录。

### 8.1 `.si` 与 `protoId.txt` 当前标准

S1-05 当前推荐以 `zero-codegen` 模块内的 `.si` 作为协议 DSL 主入口。标准文档见：

```text
zero-codegen/docs/protocol-dsl.zh-CN.md
```

`.si` 顶层块包括：

- `enum`
- `struct`
- `client_to_server:`
- `server_to_client:`

工具入口支持目录递归扫描 `.si` 文件，并通过独立 `protoId.txt` 为每个 schema 配置协议号区间：

```text
Player 1000 2000
Inventory 3000 4000
```

第一列是 schema 基名，第二列是客户端到服务端起始 ID，第三列是服务端到客户端起始 ID。客户端到服务端会规整到奇数 ID 并按 `+2` 分配；服务端到客户端会规整到偶数 ID 并按 `+2` 分配。

测试示例位于：

```text
zero-codegen/src/test/resources/protocol-dsl/sample/
```


## 9. S1-05 当前实现快照

- `zero-codegen` 已落地 `ProtocolDslParser`、`DefaultProtocolDslParser` 和 `DefaultCodeGenerator`。
- 当前保留块式 DSL 兼容入口，支持 `namespace`、`enum`、`message`、`protocol` 和 `method`。
- 当前新增 `.si` 主入口，支持目录递归、`protoId.txt`、`enum`、`struct`、`client_to_server:` 和 `server_to_client:`。
- 类型系统已支持标量、枚举、消息、`List`、`Set`、`Map`、数组、多维数组，以及 `optional` / `nullable`。
- `null` 仅保留为内部占位，不允许直接作为业务字段类型暴露。
- Java 生成侧已输出带可配置后缀的 DTO、轻量 marker、payload codec、`ProtocolIds`、`GeneratedProtocolDefinitions`、事件级 BO 接口、可选 `BOImp` 模板和协议分发器；默认包布局为 `.dto`、`.dto.codec`、`.protocol`、`.bo`、`.bo.impl`、`.protocol.dispatch`，并支持按生成物类型单独覆盖输出目录和包名。
- C#、TypeScript、GDScript 生成侧已输出客户端轻量运行时、协议号、枚举、带可配置后缀的 DTO、轻量 marker 和 payload codec，可通过 CLI/GUI 语言选项开启。
- `.si` 入口当前采用事件级 BO：`<Schema><Method>EventBO` / `<Schema><Method>EventBOImp`；分发器按协议号解码为 DTO 后调用对应 BO。
- Java 生成侧模板位于 `zero-codegen/src/main/resources/codegen/java`，使用 FreeMarker 渲染，协议读写表达式由生成模型预处理。
- CLI 与 Swing GUI 共用 `ProtocolCodegenRunner`，避免服务端、客户端和工具链入口出现重复生成逻辑。
- `zero-codegen` Maven `package` 阶段会附加 `zero-codegen-<version>-all.jar`，可通过 `--gui` 启动图形工具，也可通过 `jpackage` 包装为平台可执行文件。
- 面向客户端和活动策划的完整用户指南位于 `zero-codegen/docs/user-guide.zh-CN.md`。


## 2026-09-17 报告核实修订

Java 包名覆盖须为合法 Java 21 限定标识符，Java DTO 后缀须能组成合法标识符；非法路径输入在生成前拒绝，包目录解析后须位于配置输出根内。二进制数组和集合声明长度在分配前按剩余载荷检查；自定义集合元素读取器须至少消费 1 字节（空对象也需携带对象长度）。有效线格式不变。

## 编码缓冲生命周期

`GeneratedProtocolCodec` 与 `ZeroBinaryFrameCodec` 的同步编码复用内部临时堆缓冲。每个平台线程最多保留一个容量不超过 64 KiB 的 writer；超大缓冲丢弃，虚拟线程不缓存。借出期间缓存槽为空，嵌套编码使用独立 writer；成功或异常均归还/丢弃，返回 `byte[]` 仍是独立副本。

实现 `ZeroPayloadCodec.write` 时只能在本次同步调用内使用 writer 及其 `buffer()`、切片和 ByteBuffer 借用视图，不得保留或交给异步任务。需要长期保存的内容必须复制。重置仅清空逻辑长度，不擦除底层字节；编码器必须完整写入所有输出字段，不得读取未写入区域。

`ZeroBuffers.heap/direct/nativeMemory` 的调用方所有权不变，未引入对外通用池或 Netty 依赖。native 路径仍为显式选择，调用方需独占访问并关闭；源码使用可达性栅栏防止 Cleaner 在原始地址操作完成前回收 owner，并不支持并发 close。Java 21 的 FFM 是预览（JEP 442），正式 API 从 Java 22（JEP 454）开始；当前不启用预览，未来迁移需同时定义 Arena、切片、扩容与线程边界。

### 2026-09-23 网络缓冲入口

`ProtocolFrameCodec` 增加 `encodeTo/decodeFrom/encodedLength`；默认 Netty codec 直接连接协议 ZeroReader/ZeroWriter 与 ByteBuf，线格式与生成 DTO 不变。业务帧仍自持有数据，数组 getter 仍复制；只读视图和无复制长度用于安全消除中转。自定义 codec 的所有权及上界要求见[迁移说明](migrations/20260923-performance-plan.md)。直接 UTF-8 实验未达到吞吐要求，默认保留 JDK 字符编码。
