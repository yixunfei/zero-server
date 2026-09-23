# 2026-09-23 codegen 对接与重生成修复

## 变更与影响

本次面向 0.x 开发版本，保持 DSL、协议号、线格式、DTO 字段和 BO 方法签名。

1. Java 生成分发器的 `dispatchFrame(frame)` 延续直接读取帧私有稳定数据的路径；`dispatch(id, bytes)` 改用只读 reader，减少字符串解码中间复制。调用方须保证数组在同步分发期间稳定。
2. 两种入口均在调用 BO 前拒绝对象边界外的尾随字节，抛出 `ProtocolErrorCode.DECODE_FAILED`，与 `GeneratedProtocolCodec` 一致。对象内部追加字段仍由 codec 跳过。过去拼接多条 payload 的调用应拆成独立消息。
3. BOImp 仅首次创建，保留已有实现（包括带旧生成标记的文件）。新模板标明由用户维护；接口变化需手工适配实现并重新编译。
4. Java/C#/TypeScript/GDScript 共用生成写入策略，内容完全一致时保留修改时间，避免无效增量编译。非生成文件继续拒绝覆盖。

## 接入步骤

使用 Java 21 在仓库根执行：

```powershell
mvn -pl zero-codegen -am package
java -jar zero-codegen/target/zero-codegen-0.1.0-SNAPSHOT-all.jar --help
```

用业务项目原有生成命令（含自定义输出目录、包名、后缀及 protoId）更新协议源码，编译业务工程。GUI 同样使用更新后的 JAR；共享后端已包含上述行为。

在业务 `ServerFrameHandler` 中调用 `dispatcher.dispatchFrame(frame)`，避免先提取 `frame.payload()`；dispatcher 仍放在依赖 BO 的业务/装配模块。启动阶段完成 BO 注册并安全发布，注册与分发不得并发。

脚手架 `ProjectScaffoldCli --plan/--apply` 只管理其 ownership 清单，不能替代普通协议重新生成。普通协议生成依靠源码标记保护工具文件，不提供三路比较或整体事务；保留生成标记的 DTO/codec 手工修改仍可能被覆盖。旧布局产物不会自动删除。

## 验证与回退

2026-09-23，Windows / JDK 21 验证：

- `mvn -Pquality -pl zero-codegen,zero-logic -am verify -DskipITs`：13 个 reactor 模块成功，按测试类去重 308 项测试，失败/错误/跳过均为 0；包括 58 项 codegen 测试、脚手架升级/事务保护及真实 TCP→BO→session 流程。Checkstyle、PMD、SpotBugs、JaCoCo 通过。
- 最终补充 CLI 帮助文案和帧 DTO 所有权断言后，`mvn -Pquality -pl zero-codegen -am verify -DskipITs` 再次通过。新增的 5 项回归验证默认/自定义包与目录/DTO 后缀的生成编译执行、只读 DTO 所有权、截断/尾随拒绝且 BO 不执行、对象内追加字段、重复注册、业务源码保护及四语言同内容不写。
- 最终打包 CLI 使用 standard-flow 工程生成 173 文件：Java 77、C# 47、TypeScript 48、GDScript 1；77 个 Java 文件以 Java 21 编译成功。再次生成全部内容与修改时间保持不变，手工修改的 BOImp 保留；非法 CLI 参数退出码为 2。
- 架构守卫：56 模块、20 规则、0 违规/警告；API 守卫：5 个受保护模块通过，既有性能任务的 additive API 未变化。

本地日志、打包工具 SHA-256、命令和编译结果位于 `target/codegen-integration-20260923/`。没有将重复运行计入测试总数。

回退时恢复上一份工具 JAR 与工具管理的生成源码快照；业务 BOImp 保留，按接口进行手工合并。仅切回数组入口不会取消本次严格尾随校验。

不增加尺寸预计算或新的客户端线格式。本轮不声称 TS/.NET/Godot 独立运行、GUI 人工点击或性能吞吐增幅。
