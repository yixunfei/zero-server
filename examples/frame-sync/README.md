# 帧同步输入与提交通知示例

本例直接使用 zero-frame-sync：重复提交同一输入 → tick 消费一次 → 模拟器收到真实输入 → 产生提交事件与广播回调。submit 完成只表示输入入队，tick 才推进权威帧。

仓库根目录使用 JDK 21、Maven 3.9+，首次先安装框架依赖：

```powershell
mvn -B -ntp -DskipTests install
mvn -B -ntp -f examples/frame-sync/pom.xml clean test exec:java
```

成功摘要：

```text
frame-sync=ok|mode=local|frameNo=1|inputs=1|events=1|broadcasts=1
```

从 [FrameSyncDemo.runFlow](src/main/java/group/zn/zero/examples/framesync/FrameSyncDemo.java) 接入。示例显式选择迟到输入 REJECT、缺失输入 EMPTY，以及小型 payload、队列、未来帧和参与者预算；应用应按业务选择这些限制。

模拟和通知回调均在本地 match lane 执行。默认 LocalActorScheduler 同步完成，本例只在普通命令行线程 join；替换为异步调度器时需异步编排和关闭，不能在同一 lane 内等待自身。

回调记录的是本地通知，broadcasts=1 不代表网络送达。网络 IO、客户端预测、快照持久化与断线恢复由业务提供。[测试](src/test/java/group/zn/zero/examples/framesync/FrameSyncDemoTest.java) 核对实际 UID、输入序号、payload、提交帧和 tick traceId，避免仅计数却未消费输入。