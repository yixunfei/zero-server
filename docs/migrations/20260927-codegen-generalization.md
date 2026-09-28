# 2026-09-27 codegen 通用性升级（0.x）

实施前完整工作区备份提交：`2bf2e09`。本项目仍在开发期，本次直接升级生成格式，不引入旧格式适配层。

## 行为与迁移

1. 用 Java 21 重新构建 codegen，重新生成四端协议代码；保留业务 BOImp。有效协议线格式及原有固定向量保持一致。
2. DSL byte 统一为 -128..127；C# DTO 的 byte/byte[] 调整为 sbyte/sbyte[]，原始 bytes 仍是 byte[]。short 超界拒绝；uint/id/count 上限 2^31−1，ulong 上限 2^63−1。TS 不再截断超范围或非整数输入。
3. Java/C#/TS/GD 读取对象时约束当前对象边界；手写 reader 调用必须按 LIFO 配对 begin/end。恶意集合长度在分配前校验。Godot 使用 `is_valid/get_error`，失败 codec 返回 null，writer 失败不输出部分字节。
4. 同一输入内所有 `.si` 共享类型符号。建议对业务协议使用方法 `@id(9001)`；同一文件同一方向全部显式声明，C2S 奇数、S2C 偶数。未声明的原型仍按范围和顺序编号，不承诺重排稳定。
5. GUI 可保存 JSON 项目；CLI 用 `--config`、`--mode`、`--json`。路径相对配置文件，CLI 覆盖项相对当前目录。生成文本规范为 LF，首次再生成可能产生换行变化。
6. 默认工程输出根目录新增 `.zero-codegen/manifest.json`；已有带标记的旧生成物首次接管，之后拒绝覆盖内容摘要不符的手改文件。BOImp 保留，不记录为可清理生成物。
7. 先 `plan` 检查，再 `prune` 清理摘要一致的过期文件；手改文件/移出当前输出配置的旧文件保留。目录锁文件不能在运行期间删除。
8. 可捕获 IO 失败自动回滚；进程中断后用相同输出配置 `--mode recover`。恢复不依赖当前 DSL 可解析，但路径必须仍在配置范围内；遇到外部编辑保留现场。多文件提交不是跨文件原子快照，构建应在生成完成后启动；断电、损坏磁盘和网络文件系统持久性未承诺。

独立使用低层单后端 `render(request)` 的调用只提供该后端预检及文件替换；完整工程生命周期请使用 `DefaultCodeGenerator` / CLI / GUI。

## 验证证据

- `mvn -Pquality -pl zero-codegen -am verify -q`：通过；core/runtime/protocol/codegen 共 193 项测试（15/57/44/77），零失败、零跳过。包含全工程符号、ID 重排、来源诊断、配置往返、CLI 覆盖/只读检查、手改保护、过期清理、IO 失败回滚、并发锁和中断恢复测试。
- 四端真实生成：标准工程叠加跨文件 `WireLimits` / 显式 ID 模拟协议，共 22 messages、15 protocols。Java 编译运行；C# 8 / netstandard2.1 编译、net8 运行；TS strict CommonJS/ESM 编译、Node 和 Chrome 运行；Godot 4.7.2 运行。
- 最终多端证据目录：`target/codegen-interop/1057da2eac7e4071a22c3bf0ad379658`。非法 varint、越界 ulong/short、超长集合/presence bitmap、对象跨界及 signed byte 数组均验证。
- 固定向量：

```text
role=149593d89fee47035a6f65040874726163652d3432
snapshot=2922010154035a6f650eaab4de75eaadc0e52401020209010477696e7304054775696c6480a0abfef962
boundary=80ffff03ffffffff0fffffffff07ffffffffffffffffff01ffffffffffffffff7f
```

性能测量：Windows 本机，100 structs、四语言、609 个源码文件、3 组独立 JVM 运行。首次端到端 2.28–2.32 秒；内容不变时 0.73–0.75 秒，源码零重写。证据：`target/codegen-measure/d1d9465da9974d7ba26a60106a7559ac/measurements.json`。这里比较初次与重复生成，不是与旧版本的整体速度比较。

GD 对象回填由解释层逐字节复制改为 PackedByteArray 原生 slice/append。独立 1000 对象循环、每对象 int/string、4 轮同机前后对比：原实现 90.9–92.4 ms，优化后 4.09–4.27 ms，约 22 倍；每轮输出相同 10936 字节。证据：`target/codegen-runtime-measure/results.txt`，可复测脚本 `zero-codegen/src/test/interop/gdscript/measure_writer.gd`。仍存在随前缀长度增加的复制，不将该局部结果推广为整体编解码吞吐提升。C# 回填移除临时 List，TS 复用 UTF-8 编码器，未单独量化收益。

本机尚未验证：Unity 编辑器/IL2CPP/AOT、旧版 Godot、Linux 实际执行、GUI 人工交互。已添加 Windows/Linux CI 与 Godot 4.4.1 门禁配置，尚未触发远端运行，不能把配置视作通过证据。
