# 本地服务发现与路由候选选择

覆盖模块：zero-discovery、zero-runtime-discovery。依赖见 [pom.xml](pom.xml)，入口见 [DiscoveryExample.java](src/main/java/group/zn/zero/examples/recipes/discovery/DiscoveryExample.java) 的 `runFlow()`，测试见 [DiscoveryExampleTest.java](src/test/java/group/zn/zero/examples/recipes/discovery/DiscoveryExampleTest.java)。

## 运行

使用 JDK 21、Maven 3.9+，在仓库根目录执行。首次或框架源码更新后先运行 `mvn -B -ntp -DskipTests install`。

```powershell
mvn -B -ntp -f examples/module-recipes/discovery/pom.xml test exec:java
```

## 接入步骤

1. 只安装 `DiscoveryRuntime.module()`，启动 runtime 后取得 `SERVICE_DISCOVERY`。
2. 注册跨集群、健康/不健康、禁用和不同标签的实例。
3. `ServiceQuery` 按分组、集群和健康筛选；`selectLogic` 再按标签、enabled、正权重选择并排序。
4. 主实例下线、备用实例恢复，再按显式分组注销全部实例。

## 预期结果

selected=[logic-a]，healthyAfterOffline=0，replacement=[logic-c]，emptyAfterUnregister=true。

`main` 打印 `Result` 后退出；回归测试断言关键结果，异常不会被仅打印后忽略。

## 接入边界

`ServiceQuery` 不会自动按 metadata、enabled 或 weight 筛选，业务路由必须明确自己的选择策略。这里按 ID 排序只为稳定展示，没有实现加权负载均衡。

注册的 host/port 只是元数据，不会打开网络监听，也不证明远程服务就绪。非默认 group 必须使用三参数 `unregister`；重复注销并非幂等。这个例子仅使用本地内存目录；接入 Nacos 见现有 [四组件探针](../../multi-server-player/README.md)。

## 扩展位置

增加 metadata 标签时统一修改 `selectLogic`；切换发现后端时保留查询/选择业务逻辑，按对应 Adapter 的生命周期接入。

完整注释包含参数、结果、状态修改及线程边界；复制时保留组合根的资源关闭逻辑。返回的结果 record 和列表不可变。

返回 [七类示例索引](../README.md) 或 [全部示例](../../README.md)。
