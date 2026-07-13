# SimpleRAG 需求追踪矩阵

| 工程问题 | 机制/风险 | 设计落点 | 自动化证据 |
| --- | --- | --- | --- |
| 活动状态分散写入 | stale callback、错误 READY、跨库 handle | `ActiveKnowledgeContext`、`ActiveKnowledgeRuntime`、`IndexLifecycle` | `ActiveKnowledgeRuntimeTest`、freshness/构建竞态测试 |
| input port 泄漏检索实现 | Swing 与 engine/chunk 强耦合 | application DTO：`DocumentReference`、`SearchResultView`、`CitationView`、`IndexBuildResult` | ArchUnit：Swing/input ports 不依赖检索内部类型 |
| 后台任务各自校验 identity | 旧结果写入新页面 | `BackgroundTaskCoordinator` | ArchUnit：`MainFrame` 不依赖 `SwingWorker`；一致性 identity 测试 |
| 页面控件集中在窗口 | 页面无法独立构造和测试 | `KnowledgePanel`、`SearchPanel`、`AskPanel`、`StatusBar` | 三个 Panel 独立构造测试 |
| 扫描、分块和排名共同变化 | 修改一个策略触碰整条流水线 | scanner/reader/chunker/features/analyzer/scorers/ranking/highlight 组合 | scanner、query analyzer、搜索 golden 与真实 ONNX 测试 |
| repository 接口过宽 | 用例依赖不需要的 SQL 能力 | `KnowledgeSourceRepository`、`IndexPublicationRepository`、`FreshnessRepository`、`SettingsRepository` | application fake 测试、SQLite 一致性测试 |
| 接口拆分破坏跨表原子性 | source 与 revision 或发布指针分离 | `SqliteTransactionManager`，同一 `AppRepository` 聚合实现 | `SqliteTransactionManagerTest`、发布失败/原子性测试 |
| adapter 失败语义不一致 | 替换实现后泄漏旧索引或远程发送 | output ports + 保守失败约定 | 文件 monitor、index consistency、模拟 OpenAI JSON/SSE 回归 |
| 全量重建重复计算 | 单文件变化仍读取并向量化全部文档 | `FileFingerprint`、`DocumentIndexEntry`、`IncrementalIndexPlanner`、`IncrementalIndexBuilder` | `IncrementalIndexTest.onlyChangedFileIsEmbeddedAndDeletedChunksDisappear` |
| 删除文件留下幽灵片段 | 旧 chunks 被错误带入新索引 | planner 的 `deleted` 集合 + 完整 snapshot 重组 | 删除 entry/chunk 断言、增量/全量等价测试 |
| reader/chunker/model 变化后错误复用 | 新旧预处理或向量空间不兼容 | entry 版本 + manifest model signature 复用门禁 | planner 版本测试、model version 全量重建测试 |
| 增量算法改变检索结果 | 复用顺序或片段集合与全量构建不同 | 按当前扫描顺序组装完整 snapshot | `IncrementalIndexTest.incrementalSnapshotMatchesFullRebuild` |
| 外部变化重建覆盖旧 revision | watcher 只置 DIRTY 时新旧构建文件名相同 | freshness 条件递增 `source_revision`，继续原子发布 | `RuntimeFreshnessTest.failedIncrementalBuildKeepsPreviouslyPublishedRevisionFile` |
| Common 职责不明确 | 通用代码分散或 Common 退化为杂物目录 | `common.crypto.Digests`、`common.text.TextValues` 与五类职责映射 | `DigestsTest`、`TextValuesTest`、ArchUnit Common 依赖规则 |
| 敏捷过程只有阶段描述 | 无法从需求追踪到 Sprint 验收和改进行动 | Product Vision、Product Backlog、DoD、Sprint、Review、Retrospective | `docs/agile` 状态与测试/Review 证据 |

完整验收命令：`build-and-test.cmd`。架构规则位于 `ArchitectureTest`，运行态与发布证据位于 `application.runtime`、`consistency` 和 `adapter.out` 测试包。

五类职责映射见 [分层架构说明](architecture/LAYERED_ARCHITECTURE.md)，敏捷过程证据见 [敏捷开发文档](agile/README.md)。
