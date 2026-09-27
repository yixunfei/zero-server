# 2026-09-26 协议生成工具多端升级

## 影响

本次仍使用原有 `.si` DSL、`protoId.txt`、Java 服务端和 C#/TypeScript/GDScript 客户端后端。协议 ID、字段顺序及线格式不变；代表性消息的四端编码字节一致。

生成操作现在先渲染所有目标并预检路径和文件归属。任一目标与其他输出重名、文件/目录冲突、上级路径不是目录或既有文件缺少生成标记时，本次调用不会改写其他目标。BOImp 仍只在不存在时创建，已有业务实现保留；其他工具管理文件内容不变时不更新修改时间。预检不等同于磁盘事务：写入阶段遇到 IO 故障可能留下部分已更新文件，排除故障后重新运行即可。

GUI 语言专用输出目录留空时从总输出目录派生，Java 位于总目录，其他语言分别位于 `csharp/`、`typescript/`、`gdscript/`。之前依赖 GUI 预填绝对或相对目录的项目应检查实际输出位置；需要原布局时在语言专用目录显式填写。C# 默认命名空间与 CLI 一致，随 Java 包名派生。

旧 `zero_protocol.gd` 的嵌套 codec 在 Godot 中可能因访问外层函数及 Variant 类型推断而无法编译。本次模板修复生成源码，不改变 payload 字节；Godot 客户端必须重新生成并替换该文件。

## 迁移步骤

1. 用 Java 21 在仓库根执行 `mvn -pl zero-codegen -am package`，并使用更新后的 `zero-codegen-0.1.0-SNAPSHOT-all.jar` 重新生成四端代码。
2. 检查 GUI 或 CLI 的总输出目录、语言专用覆盖目录与命名空间；替换客户端生成源码，尤其是 `zero_protocol.gd`。布局或 DTO 后缀变化后手工移除旧路径产物，避免旧类继续参与编译。
3. 保留已有 BOImp 业务代码，按新生成的 BO 接口检查并编译服务端和客户端项目。
4. 在仓库根运行 `zero-codegen/scripts/smoke-interop.ps1 -GodotExecutable <Godot 控制台程序路径>`。需要 JDK 21、.NET 8 SDK、Node.js/npm 和 Godot 4；不提供 Godot 时脚本会报告运行验证未覆盖。

## 验证边界

标准 `.si` 工程覆盖负数、枚举、字符串、嵌套对象、集合、Map、可选字段和 `ulong`；Java、C#、TypeScript、Godot 4.7.2 各自编译并执行两组往返，字节与固定向量一致：

```text
role=149593d89fee47035a6f65040874726163652d3432
snapshot=2922010154035a6f650eaab4de75eaadc0e52401020209010477696e7304054775696c6480a0abfef962
```

这些向量不覆盖全部 DSL 类型或业务项目的 Godot 版本。涉及 `Set` / `Map` 的跨端确定顺序仍由业务数据和显式排序保证。回退时恢复上一版 JAR 和工具管理源码快照；业务 BOImp 不应被回退覆盖。
