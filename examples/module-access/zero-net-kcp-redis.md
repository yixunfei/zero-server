# zero-net-kcp-redis 接入卡片

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-net-kcp-redis</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

KCP + Redis 状态组合。

核心 API：`RedisKcpSessionStore`。

外部组件入口；命令的实际覆盖范围及环境前置条件见下方说明。

从 [RedisKcpSessionStoreTest.java](../../zero-net-kcp-redis/src/test/java/group/zn/zero/net/kcp/redis/RedisKcpSessionStoreTest.java) 的 **completesAtomicMigrationWhenRedisIsAvailable** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -pl zero-net-kcp-redis -am "-Dtest=RedisKcpSessionStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

显式设置 ZERO_REDIS_URI，使用隔离 Redis。未设 ZERO_REDIS_URI 且默认 Redis 不可达时会跳过；显式配置不可达地址则失败。必须核对 surefire 报告。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../README.md)、[模块图](../../docs/module-map.md)。
