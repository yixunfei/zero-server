# zeroServer `.si` DSL 标准格式

本文是随 `zero-codegen` 模板一起打包的 DSL 标准格式说明，用于让工具、文档生成和示例保持同一份语法口径。

## 文件结构

一个 `.si` 文件以文件名作为 schema 名称，不在文件内声明 namespace。标准顶层块顺序建议如下：

```si
# 文件级说明，可选。

enum EnumName {
  VALUE = 0,
  OTHER = 1
}

struct StructName {
  int fieldName; // 字段说明
  Optional<String> nullableText;
}

client_to_server:
  methodName(long uid, StructName payload);

server_to_client:
  methodResult(int code, String message);
```

## 顶层块

- `enum`：声明协议枚举，枚举值可以显式赋值，也可以从 `0` 自动递增。
- `struct`：声明协议 DTO 字段，字段顺序即线格式顺序。
- `client_to_server:`：声明客户端到服务端协议方法。
- `server_to_client:`：声明服务端到客户端协议方法。

当前不支持 `import`、`include`、文件内 `namespace`、块注释或宏系统。

## 注释与行尾

- `#` 支持整行注释。
- `//` 支持行尾注释，行尾注释会进入字段或方法注释。
- 字段、枚举值和方法声明可以用 `;` 或 `,` 结尾。
- 全角冒号会被归一化为半角冒号。

## 类型

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
- `List<T>` / `ArrayList<T>` / `LinkedList<T>` / `Collection<T>`
- `Set<T>` / `HashSet<T>` / `LinkedHashSet<T>`
- `Map<K,V>` / `HashMap<K,V>` / `LinkedHashMap<K,V>` / `Dictionary<K,V>`
- 数组：`T[]`
- 多维数组：`int[][]`
- nullable：`Optional<T>` / `optional<T>` / `nullable<T>` / `T?`

## 协议号

生产项目应配套维护 `protoId.txt`：

```text
Player 1000 2000
Inventory 3000 4000
```

每行三列分别是 schema 基名、`client_to_server` 起始 ID、`server_to_client` 起始 ID。

分配规则：

- `client_to_server` 从奇数 ID 开始，按 `+2` 分配。
- `server_to_client` 从偶数 ID 开始，按 `+2` 分配。
- 同一工程内协议 ID 和协议名称必须唯一。

## Java 生成物

Java 后端当前生成：

- DTO：`<Struct>.java`、`<Schema><Method>Protocol.java`
- payload codec：`codec/<Message>Codec.java`
- 协议号：`ProtocolIds.java`
- 协议定义：`GeneratedProtocolDefinitions.java`
- 事件接口：`bo/<Schema><Method>EventBO.java`
- 默认实现：`bo/impl/<Schema><Method>EventBOImp.java`
- 分发器：`bo/GeneratedProtocolDispatcher.java`

分发器按协议号读取 payload，使用生成的 codec 解码为 DTO，然后调用对应 `XXXEventBO`。
