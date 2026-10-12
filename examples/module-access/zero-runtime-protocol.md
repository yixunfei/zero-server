# zero-runtime-protocol 接入卡片

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-runtime-protocol</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

协议 runtime provider。

核心 API：`ProtocolRuntime.module`。

可运行测试入口；同时阅读 fixture、依赖注入与断言，不是独立服务程序。

从 [ZeroServerFullLocalDemoStart.java](../../zero-server-starter/src/main/java/group/zn/zero/starter/ZeroServerFullLocalDemoStart.java) 的 **registerDemoComponents；provider 选择见同模块 LocalRuntimeProviders.defaults** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -pl zero-server-starter -am "-Dtest=ZeroServerApplicationTest#fullLocalDemoStartShouldExerciseRuntimeComponents" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

通过 Starter 组合根安装 provider。完整示例依赖 Starter；单个 runtime 坐标不包含完整组合根。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../README.md)、[模块图](../../docs/module-map.md)。
