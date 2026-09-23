# frame-sync 示例

这是一个独立、可重复运行的 JDK 21 Maven 示例，演示本地单 owner 帧同步最小流程：

1. 创建 match 并加入玩家
2. 提交输入和重复输入（幂等）
3. 缓存 future frame 输入
4. 按 frameNo 推进并处理 missing input
5. 展示 late policy、snapshot digest 和 close

运行：

```bash
mvn -B -ntp -f examples/frame-sync/pom.xml clean test
mvn -B -ntp -f examples/frame-sync/pom.xml exec:java
```

`exec:java` 输出一行稳定 marker：

`frame-sync=ok|mode=local|...|productionReady=false`

该示例不创建线程池、不接入网络或持久化，也不代表生产吞吐、SLA、rollback、跨服或可靠传输能力。
