# zero-runtime-log 接入卡片

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-runtime-log</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

日志 runtime provider。

核心 API：`LogRuntime.module`。

可运行测试入口；同时阅读 fixture、依赖注入与断言，不是独立服务程序。

从 [LogRuntimeTest.java](../../zero-runtime-log/src/test/java/group/zn/zero/runtime/log/LogRuntimeTest.java) 的 **pipelineUsesSelectedConfigurationAndSinkWithoutCreatingReplacedDefault** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -pl zero-runtime-log -am "-Dtest=LogRuntimeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

本入口不需要外部中间件。验证通过只代表这里展示的本地流程或契约。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../README.md)、[模块图](../../docs/module-map.md)。
