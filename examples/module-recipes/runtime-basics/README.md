# 基础运行时与受管任务

覆盖模块：zero-core、zero-runtime、zero-runtime-bootstrap。依赖见 [pom.xml](pom.xml)，入口见 [RuntimeBasicsExample.java](src/main/java/group/zn/zero/examples/recipes/basics/RuntimeBasicsExample.java) 的 `runFlow()`，测试见 [RuntimeBasicsExampleTest.java](src/test/java/group/zn/zero/examples/recipes/basics/RuntimeBasicsExampleTest.java)。

## 运行

使用 JDK 21、Maven 3.9+，在仓库根目录执行。首次或框架源码更新后先运行 `mvn -B -ntp -DskipTests install`。

```powershell
mvn -B -ntp -f examples/module-recipes/runtime-basics/pom.xml test exec:java
```

## 接入步骤

1. 用 `MapZeroConfig` 声明业务配置，必填用 `require`，可选值用 `getOrDefault`。
2. `RuntimeBasics.module(config, supplier)` 延迟创建框架执行器；`diagnose()` 只规划依赖。
3. `build()`、`start()` 后向 `logicExecutor()` 提交任务，并在命令行线程限时等待。
4. `try-with-resources` 关闭 runtime，检查 `RuntimeState.CLOSED`。

## 预期结果

组件数 2，region=local-cn，language=zh-CN，worker 以 recipe-basics-logic- 开头，started=true，stopped=true。

`main` 打印 `Result` 后退出；回归测试断言关键结果，异常不会被仅打印后忽略。

## 接入边界

`RuntimeBasics.builder()` 默认采用直接执行器；本例显式选择 `ZeroRuntimeExecutors.localPrototype` 展示实际线程切换。业务不新建或自行关闭线程池。生产业务应避免在逻辑线程执行阻塞 IO，调用方在任务完成后才能消费结果。诊断及输出不打印配置快照。

测试额外验证诊断不调用执行器工厂、缺少必填配置返回 `CONFIGURATION_ERROR`。

## 扩展位置

增加业务配置时在读取处定义必填/默认语义；要安装其他能力，继续调用 `install(...)`。

完整注释包含参数、结果、状态修改及线程边界；复制时保留组合根的资源关闭逻辑。返回的结果 record 和列表不可变。

返回 [七类示例索引](../README.md) 或 [全部示例](../../README.md)。
