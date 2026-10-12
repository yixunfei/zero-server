# zero-runtime-net 示例

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-runtime-net</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

网络 runtime provider。

核心 API：`NetworkRuntime.module`。

可运行测试入口；同时阅读 fixture、依赖注入与断言，不是独立服务程序。

从 [NetworkRuntimeTest.java](../src/test/java/group/zn/zero/runtime/net/NetworkRuntimeTest.java) 的 **independentModuleCarriesResolvedSettingsIntoTheCreatedLifecycle** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -pl zero-runtime-net -am "-Dtest=NetworkRuntimeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

验证模块装配和解析配置，未启动监听端口。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../../examples/README.md)、[模块图](../../docs/module-map.md)、[接入卡片](../../examples/module-access/zero-runtime-net.md)。
