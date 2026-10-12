# zero-parent 示例

## 依赖选择

该模块用于工程继承，放在应用 POM 的 `<parent>`，不写入 `<dependencies>`。

```xml
<parent>
  <groupId>group.zn.zero</groupId>
  <artifactId>zero-parent</artifactId>
  <version>0.1.2-SNAPSHOT</version>
</parent>
```

## 接入入口

Java 21、测试和质量插件。

核心 API：`parent、pluginManagement`。

构建配置示例；Parent/BOM/生成器通过 POM 接入，不是 Java 业务对象。

从 [pom.xml](../../examples/rpg-minimal/pom.xml) 的 **parent / pluginManagement** 开始。保留完整示例或测试中的 imports、依赖和资源关闭逻辑。
上述单模块坐标用于选择能力；复制整个场景时请同时参考它的 POM，按实际组合声明其他模块。

## 验证

在仓库根目录使用 JDK 21、Maven 3.9+。独立 examples 首次运行前先执行 `mvn -B -ntp -DskipTests install` 安装当前框架依赖。

```powershell
mvn -B -ntp -f examples/rpg-minimal/pom.xml test
```

本入口不需要外部中间件。验证通过只代表这里展示的本地流程或契约。

线程与资源归属以源码注释为准；业务不自行创建线程池，远程 IO 不在 Actor/Netty 线程同步等待。

相关入口：[根示例索引](../../examples/README.md)、[模块图](../../docs/module-map.md)、[接入卡片](../../examples/module-access/zero-parent.md)。
