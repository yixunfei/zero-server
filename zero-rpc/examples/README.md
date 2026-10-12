# zero-rpc 示例

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-rpc</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

服务绑定、路由和 transport SPI。

核心 API：`RpcClientFactory、RpcServiceBinder`。

本地可运行示例；按调用入口阅读业务流程与资源关闭。

从 [CenterLogicApplication.java](../../examples/modular-composition/center-logic/src/main/java/group/zn/zero/examples/composition/CenterLogicApplication.java) 的 **runDemo / CenterRpc** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -f examples/modular-composition/center-logic/pom.xml test exec:java
```

契约注解和 RpcResult 属于 zero-rpc-common；绑定和调用端还需 zero-rpc。复制完整 POM 及 codec 注册。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../../examples/README.md)、[模块图](../../docs/module-map.md)、[接入卡片](../../examples/module-access/zero-rpc.md)。
