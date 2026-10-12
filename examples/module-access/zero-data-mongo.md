# zero-data-mongo 接入卡片

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-data-mongo</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

MongoDB 数据 Adapter。

核心 API：`MongoDataAdapter、MongoDriverSettings`。

外部组件入口；命令的实际覆盖范围及环境前置条件见下方说明。

从 [RepositoryCompositionApplication.java](../repository-composition/src/main/java/group/zn/zero/examples/repository/RepositoryCompositionApplication.java) 的 **runtime("mongo")** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -f examples/repository-composition/pom.xml -Prepository-external "-Dzero.repository.backend=mongo" verify
```

先按场景 README 设置隔离数据库连接。普通 test 仅覆盖内存组合；实际驱动证据需检查 failsafe 报告没有跳过。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../README.md)、[模块图](../../docs/module-map.md)。
