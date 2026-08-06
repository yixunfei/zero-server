# Zero Binary Protocol、Protobuf 与 FlatBuffers 对比报告

测试日期：2026-08-06

## 1. 结论摘要

本报告在同一逻辑 DTO、同一 JVM 和同一机器上比较 Zero Binary Protocol、Protobuf 与 FlatBuffers 的编码体积、编码、完整字段扫描解码、热字段读取和往返耗时。

- 本 workload 中，zero-proto 的平均 payload 最小，编码、完整解码和往返最快。
- Protobuf 相对 zero-proto：体积约 1.14×、编码耗时约 2.51×、完整解码约 2.92×、往返约 2.85×。
- FlatBuffers 的三个热字段读取约为 zero-proto 的 0.49× 耗时，但体积约 2.01×、编码约 2.45×、完整解码约 2.06×、往返约 2.23×。
- 结果只代表该 DTO workload 的方向性微基准，不代表三种协议在所有场景的通用排名。

## 2. 环境

| 项目 | 值 |
| --- | --- |
| CPU | AMD Ryzen 9 7950X，16 核 / 32 线程 |
| 内存 | 31.2 GiB |
| OS | Windows 11 专业版 10.0.22631，64-bit |
| Java | Oracle Java 21.0.4 LTS，HotSpot 64-Bit Server VM |
| Protobuf runtime/generator | 4.35.0 |
| FlatBuffers runtime/generator | 25.2.10 |
| Benchmark | 手写轻量 micro benchmark，非 JMH |

## 3. 数据模型

每个逻辑样本包含：

- `int sequence`、`long playerId`、`int sceneId`、`float x`、`double y`。
- `string name`。
- nullable `string note`，一半样本为 null。
- 64 字节 blob。
- 16 个 int、8 个 long、12 个 boolean。
- 6 个 item 子对象，每个含 `int/int/long/double/string`。
- 6 个 key/value 属性对象。

共生成 1024 个不同样本并循环读取，避免只测单一常量对象。

实现口径：

- zero-proto 使用 `ZeroWriter` / `ZeroReader` heap byte[] 路径，复用 writer。
- Protobuf 使用 `protoc` 生成 Java 类，由同一逻辑 DTO 构建 message。
- FlatBuffers 使用 `flatc` 生成 Java 类，由同一逻辑 DTO 构建 buffer。
- Full Decode 对三者读取全部业务字段并计算校验和。
- Hot Decode 只读取 `sequence/playerId/sceneId`。
- Roundtrip 编码后立即执行完整字段扫描解码。

## 4. 参数

```text
iterations=200000
warmupRounds=4
measureRounds=5
sampleCount=1024
```

每个 codec 在同一 JVM 内完成预热和测量。结果是五轮总耗时除以总操作数；volatile blackhole 和校验和用于降低死代码消除风险。

## 5. 结果

发布前最终复验：

| Codec | Avg Size(bytes) | Encode(ns/op) | Full Decode(ns/op) | Hot Decode(ns/op) | Roundtrip(ns/op) |
| --- | ---: | ---: | ---: | ---: | ---: |
| zero-proto | 437.46 | 353.47 | 296.71 | 12.18 | 669.83 |
| protobuf | 497.63 | 885.53 | 866.55 | 825.26 | 1905.95 |
| flatbuffers | 880.74 | 865.07 | 610.47 | 5.94 | 1495.04 |

第二次正式复跑：

| Codec | Avg Size(bytes) | Encode(ns/op) | Full Decode(ns/op) | Hot Decode(ns/op) | Roundtrip(ns/op) |
| --- | ---: | ---: | ---: | ---: | ---: |
| zero-proto | 437.46 | 352.60 | 295.27 | 11.99 | 664.38 |
| protobuf | 497.63 | 870.59 | 952.80 | 913.84 | 1955.17 |
| flatbuffers | 880.74 | 894.34 | 639.45 | 6.41 | 1492.39 |

第一次正式复跑，排序一致：

| Codec | Avg Size(bytes) | Encode(ns/op) | Full Decode(ns/op) | Hot Decode(ns/op) | Roundtrip(ns/op) |
| --- | ---: | ---: | ---: | ---: | ---: |
| zero-proto | 437.46 | 359.64 | 295.67 | 11.89 | 670.88 |
| protobuf | 497.63 | 872.43 | 969.35 | 899.13 | 1965.82 |
| flatbuffers | 880.74 | 886.79 | 630.30 | 5.87 | 1483.04 |

以最终复验 zero-proto 为 1.00×：

| Codec | Size | Encode | Full Decode | Hot Decode | Roundtrip |
| --- | ---: | ---: | ---: | ---: | ---: |
| zero-proto | 1.00× | 1.00× | 1.00× | 1.00× | 1.00× |
| protobuf | 1.14× | 2.51× | 2.92× | 67.76× | 2.85× |
| flatbuffers | 2.01× | 2.45× | 2.06× | 0.49× | 2.23× |

## 6. 复现步骤

以下 PowerShell 命令把所有下载和生成内容放入被 Git 忽略的 `target/codec-comparison`：

```powershell
$ProgressPreference='SilentlyContinue'
$root='target\codec-comparison'
New-Item -ItemType Directory -Force -Path `
  "$root\deps", `
  "$root\generated\protobuf", `
  "$root\generated\flatbuffers", `
  "$root\classes" | Out-Null

Invoke-WebRequest `
  -Uri 'https://repo.maven.apache.org/maven2/com/google/protobuf/protobuf-java/4.35.0/protobuf-java-4.35.0.jar' `
  -OutFile "$root\deps\protobuf-java-4.35.0.jar"
Invoke-WebRequest `
  -Uri 'https://repo.maven.apache.org/maven2/com/google/protobuf/protoc/4.35.0/protoc-4.35.0-windows-x86_64.exe' `
  -OutFile "$root\deps\protoc.exe"
Invoke-WebRequest `
  -Uri 'https://repo.maven.apache.org/maven2/com/google/flatbuffers/flatbuffers-java/25.2.10/flatbuffers-java-25.2.10.jar' `
  -OutFile "$root\deps\flatbuffers-java-25.2.10.jar"
Invoke-WebRequest `
  -Uri 'https://github.com/google/flatbuffers/releases/download/v25.2.10/Windows.flatc.binary.zip' `
  -OutFile "$root\deps\flatc.zip"
Expand-Archive "$root\deps\flatc.zip" "$root\deps\flatc" -Force

& "$root\deps\protoc.exe" `
  --java_out="$root\generated\protobuf" `
  zero-protocol/src/benchmark/proto/codec_comparison.proto
& "$root\deps\flatc\flatc.exe" `
  --java `
  -o "$root\generated\flatbuffers" `
  zero-protocol/src/benchmark/fbs/codec_comparison.fbs

$sources = @(rg --files `
  zero-core/src/main/java `
  zero-protocol/src/main/java `
  zero-protocol/src/benchmark/java `
  "$root\generated\protobuf" `
  "$root\generated\flatbuffers" | Where-Object { $_ -like '*.java' })
$cp="$root\deps\protobuf-java-4.35.0.jar;$root\deps\flatbuffers-java-25.2.10.jar"
javac -encoding UTF-8 -cp $cp -d "$root\classes" $sources

$cp="$root\classes;$cp"
java -cp $cp `
  group.zn.zero.protocol.benchmark.CodecComparisonBenchmark `
  200000 4 5 1024
```

macOS/Linux 可以使用对应版本的 `protoc` 和 `flatc`，并把 classpath 分隔符从 `;` 改成 `:`。

## 7. 局限

- 手写 benchmark 没有 JMH fork 隔离、置信区间和 profiler。
- 未测量 GC、allocation、p99、CPU counter、压缩、加密、网络 Frame、RPC envelope 或写盘。
- FlatBuffers 的主要优势场景包括随机访问、共享 ByteBuffer 和资源数据；完整 DTO roundtrip 不是其全部能力。
- Protobuf 如果直接作为业务对象，构建路径会不同，但会改变业务层模型边界。
- 结果不能直接外推到 C#、TypeScript、GDScript 或其他 CPU/JVM。
- 线上选择还必须考虑兼容治理、调试能力、生态、跨语言和安全边界。
