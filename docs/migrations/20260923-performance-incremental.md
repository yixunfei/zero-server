# 2026-09-23 性能增量迁移

适用：0.1.0-SNAPSHOT，S0-S3 之后的 EventBus、AOI、协议读取、排行榜快照和缓冲搬移优化。

业务 BO/DTO、Actor、EventBus、AOI、Ranking 的既有调用无需修改；无新依赖、线程池、必填配置或 JVM 参数。协议线格式及 `ProtocolFrame` record 的组件、构造器、反射表示保持不变。

## 协议输入与生成器

- `ZeroReader(ByteBuffer)` 对**只读**输入借用 remaining 区间，不再复制 heap 内容，返回的 `ZeroBufferSlice` 不能通过 `array()` 获得可写存储。不要依赖只读输入被自动复制为可变数组的旧实现细节。读取期间须保证内容稳定；需要独立快照时显式复制。可写 heap 输入仍复制，可写 direct 输入仍按已有规则借用。
- `ProtocolFrame.payloadView()` 自持有且不可变，可以跨异步保留。新增 `payloadReader()` 直接封装私有数组为只读 reader，省掉 ByteBuffer 适配及 UTF-8 字段中间字节副本；切片和自定义 Charset 均不能取得可写内部数组，不需要 retain/release。`ZeroReader.readOnly(byte[])` 只读借用工厂要求数组所有者保证内容稳定。
- 新增 `ProtocolCodec.decodeView(definition, payload, messageType)`。已有自定义 codec 无需新增实现，默认复制一次后调用既有数组入口；`GeneratedProtocolCodec` 直接使用只读 reader，并保持类型、截断及尾随字节校验。
- Java 生成器新增 `GeneratedProtocolDispatcher.dispatchFrame(frame)`，内部使用 `frame.payloadReader()` 直接读取只读 payload，BO 签名不变。原 `dispatch(int, byte[])` 入口继续可用。使用项目原有协议生成命令更新工具管理的文件；脚手架升级不自动重生成协议源码。已有 BO 实现由用户维护并保留，详细步骤见 [codegen 对接迁移](20260923-codegen-integration.md)。网络组合根可直接调用 `dispatchFrame`，无需先取 `frame.payload()`。
- 构造 Frame 接收可变数组时仍防御复制；生成编码和网络发送的同步快照时机保持不变。这里减少部分用户态复制，不代表 TCP/TLS 到 DTO 的零复制。

## 内部优化与语义

EventBus 在注册时重建同版本的不可变快照，发布时只读一次；回调在注册锁外。已完成的标准 Future 迭代处理，通用 CompletionStage 保持异步串行完成；失败继续派发、死信与异常聚合保持。每次发布的完成信号独立。

AOI 合并观察者快照与原始序号，场景只复用一份候选工作区，超过 4096 项的查询不保留工作区；每次归还均清空元素引用，数组容量遵循 ArrayList 的扩容规则。每个可见实体增加内部代次记录，随观察者释放。场景序号、中心、范围均相同且观察状态引用稳定时直接返回空列表；相等但不同引用的可变状态仍执行原有值比较，避免遗漏此前可观察的变化；实体变更继续调用现有 `update`，不增加标脏步骤。事件仍按 ENTER/UPDATE/LEAVE 和 String ID 排序，历史结果不复用、不修改。

排行榜每个榜单最多缓存最近一次 TopN 的 100 个引用，写入提交立即失效；默认查询仍精确。**仍保留服务全局锁及同步通知**，没有消除多榜串行瓶颈，也没有新增通知队列。事件异常仍在状态提交后向调用者传播，幂等重试不重复通知。

direct 及单段 Netty 内存的重叠搬移使用 Java 21 保证的批量 put 语义；heap 使用数组搬移，组合 ByteBuf 保留通用路径。native 重叠搬移不引入未经证明的 Unsafe 假设。Actor 延迟包装实验没有明确分配收益，未保留源码改动；计数、FIFO、异步占用、拒绝和关闭约定保持。

## 验证与回退

验证证据与未覆盖范围见[实施报告](../reports/performance-incremental-20260923.zh-CN.md)。回退应恢复本批框架及生成器产物的一致版本；若组合根使用新增入口，同时恢复原数组分发调用。无需数据迁移。

2 小时长稳、Linux EPOLL、跨机 RPC、生产 TLS 容量以及跨异步缓冲借用不由本批短测证明。
