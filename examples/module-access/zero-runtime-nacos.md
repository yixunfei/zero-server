# zero-runtime-nacos 接入卡片

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-runtime-nacos</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

Nacos runtime provider。

核心 API：`NacosRuntime.module`。

已有组合根的实现参考，并配有可运行的配置/装配契约测试。

从 [ZeroProductionRuntimeBuilder.java](../../zero-server-starter-production/src/main/java/group/zn/zero/starter/production/ZeroProductionRuntimeBuilder.java) 的 **assembly** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -pl zero-server-starter-production -am "-Dtest=ProductionRuntimeAssemblyContractTest#nacosRequestTimeoutShouldUseRuntimeBudgetAndRejectExplicitOverflow" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

此链接是 Starter 的装配实现参考，命令验证配置/装配契约，不要求外部服务可达，也不运行启动健康检查。按需组合需要配置、受管 IO、codec 与生命周期。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../README.md)、[模块图](../../docs/module-map.md)。
