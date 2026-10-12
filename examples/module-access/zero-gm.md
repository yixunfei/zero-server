# zero-gm 接入卡片

## 依赖选择

```xml
<dependency>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-gm</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</dependency>
```

## 接入入口

GM DSL、dry-run、审批和审计。

核心 API：`GmCommandDsl、GmCommandExecutor`。

可直接运行的独立应用示例：[接入步骤与常见场景](../module-recipes/gm/README.md)。

从 [GmExample.java](../module-recipes/gm/src/main/java/group/zn/zero/examples/recipes/gm/GmExample.java) 的 **runFlow()** 开始；[完整 POM](../module-recipes/gm/pom.xml) 展示实际组合依赖。单模块坐标用于选择能力，不等于整个场景所需依赖。

真实 REST 适配到授权/审批/幂等/审计链，但使用本地身份 fixture 和内存请求，不监听 HTTP 端口。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。首次运行或框架源码变化后先执行 `mvn -B -ntp -DskipTests install`。

```powershell
mvn -B -ntp -f examples/module-recipes/gm/pom.xml test exec:java
```

`main` 输出不可变结果并退出，测试验证关键状态与拒绝路径。更底层的原契约入口仍可参考 [GmCommandExecutorTest.java](../../zero-gm/src/test/java/group/zn/zero/gm/GmCommandExecutorTest.java)。

线程、状态和资源归属见代码注释与场景 README；业务不自行创建线程池，Actor/事件回调不阻塞等待同 lane。

相关入口：[七类接入示例](../module-recipes/README.md)、[全部模块卡片](README.md)、[模块图](../../docs/module-map.md)。
