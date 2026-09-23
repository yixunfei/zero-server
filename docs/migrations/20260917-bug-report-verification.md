# 2026-09-17 报告核实修复迁移说明（0.x）

本次直接修正开发阶段实现，不增加旧行为兼容层。用户已确认跨缓存、持久化、事件、协议与 RPC 的修复范围，并明确选择监听器失败后继续派发。

## 调用方需要调整的行为

1. **事件**：不要依赖某个业务监听器异常阻止后续监听器。拦截器仍可拒绝整次发布。publish 等所有处理器后才以汇总失败结束；默认不自动重试。死信是有界本地历史，默认 1024，可配置；需要可靠重放时提供持久化 DeadLetterSink。
2. **持久化**：显式 start 后使用，并在 actor 快照执行域及仓库关闭之前 stop；停止会等待脏对象保存。默认预算 30 秒，可通过新构造器设置 Duration。必须观察 stop 失败并处理残留脏对象。SPI 应立即返回 CompletionStage，框架不为同步阻塞 SPI 新建线程或强制抢占。并发 flush 合并在途结果，新登记入口由后续批次处理。
3. **缓存**：同步 loader 失效后会重新执行；显式写入/失效优先于旧加载结果回填。L2 回填不重置 TTL，不覆盖 L1 新版本；读到更高版本后本地生成器继续递增。非法 null loader 结果明确失败，不再永远等待。
4. **帧同步**：当前已提交帧也算迟到；REJECT 重传仍拒绝，BUFFER 转下一帧，MARK 不进入模拟。替换同一 uid/targetFrame 不额外占容量。去重只保留 maxBufferedInputs 个近期键，历史重传遵守时序策略。内部 SubmitInput/AdvanceFrame 公共嵌套记录被移除，业务通过 submit/tick 调用。共享 scheduler 路由在 scheduler 生命周期内保留无状态处理器；close 只关闭对应对局。
5. **房间**：snapshot.members 不再含 LEFT；通过事件获知离开。close 后仍能查最终状态，业务归档结束后显式 destroy(id)，只有 CLOSED 可释放。内存历史最近 1024 条，超出部分累计到 droppedEventCount。消费者异常向调用者传播，但已提交状态不回滚；不能无条件重试非幂等业务副作用。
6. **状态同步/AOI**：处理新的 IGNORED 状态；旧消息不应再应用到客户端。基线区分 sceneId+observerId。没有变化时 observe 可返回空列表；LEAVE 提供最后可见实体。
7. **排行榜**：ADD 溢出改为 RANKING_SCORE_REJECTED。snapshot 仅接受 FROZEN 赛季，与现有文档一致；普通实时展示用 queryTop/queryPlayerRank。Top-N 上限仍为 100，不引入分页。
8. **协议/生成器**：有效二进制编码不变，畸形长度更早失败；自定义集合元素读取器每项至少消费一个线字节，空对象也应带长度前缀。修正无效的 Java 包名、文件名后缀；输出根可按语言/生成物配置，但包名不能充当输出路径。Kafka envelope 不再接受尾随字节。
9. **指标**：InMemoryMetricRegistry 默认保留最近 4096 个样本，可配置容量，通过 droppedSamples 观测丢弃历史。需要长期聚合时使用合适的 MetricRegistry 实现，不依赖历史列表无限增长。
10. **RPC/TLS**：RPC 等待只使用剩余预算；受控传输调用消耗的时间也计入预算。TLS_ESTABLISHED 正确按 Optional 内布尔值判断，不要求调用方改变已正确设置的属性。

## 验证与回退资料

完整结论：[逐项核实报告](../reports/bug-analysis-verification-20260917.zh-CN.md)。公开报告提供测试复现命令；原始日志、SHA-256 备份清单和原样文件只保存在维护者本地档案，不随仓库分发。

这是开发阶段行为修复，没有修改有效协议 ID、数据库格式或模块依赖方向。回退时依据对应变更与迁移条目人工选择文件，避免覆盖备份之后的新修改；不要批量 reset 工作区。
