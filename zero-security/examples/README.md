# zero-security 示例

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-security</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

认证、重放、TLS 和安全上下文。

核心 API：`SecurityContext、SecurityChain`。

本地可运行示例；按调用入口阅读业务流程与资源关闭。

从 [KcpDemoServer.java](../../examples/kcp-tcp-login/src/main/java/group/zn/zero/examples/kcp/KcpDemoServer.java) 的 **start** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -f examples/kcp-tcp-login/pom.xml compile exec:java "-Dexec.args=--demo ALL"
```

启动本机 TCP/UDP 并在演示结束时关闭。该场景没有独立单元测试，仅执行 test 不会运行链路。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../../examples/README.md)、[模块图](../../docs/module-map.md)、[接入卡片](../../examples/module-access/zero-security.md)。
