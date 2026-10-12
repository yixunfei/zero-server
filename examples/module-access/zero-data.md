# zero-data 接入卡片

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-data</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

Repository 与 DataService。

核心 API：`CrudRepository、DataService`。

本地可运行示例；按调用入口阅读业务流程与资源关闭。

从 [DailyRewardService.java](../business-slice/src/main/java/group/zn/zero/examples/business/DailyRewardService.java) 的 **claim / loadInventory；装配见同目录 BusinessSliceApplication** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -f examples/business-slice/pom.xml test exec:java
```

本入口不需要外部中间件。验证通过只代表这里展示的本地流程或契约。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../README.md)、[模块图](../../docs/module-map.md)。
