# zero-ownership-postgresql 示例

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-ownership-postgresql</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

PostgreSQL ownership Adapter。

核心 API：`PostgresqlOwnershipStore`。

外部组件入口；命令的实际覆盖范围及环境前置条件见下方说明。

从 [PostgresqlOwnershipExternalIT.java](../src/test/java/group/zn/zero/ownership/postgresql/PostgresqlOwnershipExternalIT.java) 的 **stateCasAndReplayAreDurable** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -Pexternal-tests -pl zero-ownership-postgresql -am "-Dit.test=PostgresqlOwnershipExternalIT" "-Dfailsafe.failIfNoSpecifiedTests=false" verify
```

需要 ZERO_EXTERNAL_POSTGRES_URL、ZERO_EXTERNAL_POSTGRES_USER 及 ZERO_EXTERNAL_POSTGRES_PASSWORD / ZERO_EXTERNAL_POSTGRES_PASSWORD_FILE 配置；缺配置会跳过，需核对 failsafe 报告。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../../examples/README.md)、[模块图](../../docs/module-map.md)、[接入卡片](../../examples/module-access/zero-ownership-postgresql.md)。
