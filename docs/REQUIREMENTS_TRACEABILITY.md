# 第二阶段需求追踪矩阵

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

完整验收命令：`build-and-test.cmd`。架构规则位于 `ArchitectureTest`，运行态与发布证据位于 `application.runtime`、`consistency` 和 `adapter.out` 测试包。
