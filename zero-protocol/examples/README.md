# zero-protocol 示例

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-protocol</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

协议、frame 和 codec SPI。

核心 API：`ProtocolRegistry、ProtocolFrameCodec`。

本地可运行示例；按调用入口阅读业务流程与资源关闭。

从 [RpgTcpGeneratedApplication.java](../../examples/rpg-tcp-generated/src/main/java/group/zn/zero/examples/rpgtcp/RpgTcpGeneratedApplication.java) 的 **runDemo / createFrameHandler** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -f examples/rpg-tcp-generated/pom.xml test exec:java
```

本入口不需要外部中间件。验证通过只代表这里展示的本地流程或契约。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../../examples/README.md)、[模块图](../../docs/module-map.md)、[接入卡片](../../examples/module-access/zero-protocol.md)。
