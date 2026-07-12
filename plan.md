# SimpleRAG 下一阶段扩展与优化计划

## 1. 当前基线

上一轮一致性改造已经完成，当前系统具备：

- `source_revision`、`published_index_revision` 和明确的 `IndexStatus`。
- 带完整 manifest 的 revision 索引文件。
- 模型文件签名、预处理版本和向量维度兼容检查。
- 独立 builder、临时文件、原子移动和 SQLite 条件发布。
- 构建失败、取消和启动恢复时保留上一已发布版本。
- 搜索、问答和 Swing 后台任务绑定 `knowledgeBaseId + sourceRevision`。
- 递归 `WatchService`、动态子目录注册、debounce、`OVERFLOW` 回退和周期性 reconciliation。
- watcher 事件可以在同一应用会话内把活动索引条件标记为 `DIRTY`。
- 远程 RAG 在召回前和 HTTP 发送前分别执行 freshness gate；freshness 无法证明时保守拒绝。
- SQLite schema 3 记录 `last_verified_source_hash`、`last_verified_at` 和 freshness 原因。
- UI 显示源文件变化原因和最近核对时间。
- application ports、基础设施 adapters、Swing controllers 和 `AppCompositionRoot`。
- JUnit 5、ArchUnit、真实 ONNX 回归测试和模拟 OpenAI JSON/SSE 测试。

运行期 freshness 阶段已经完成并通过同会话修改/删除、动态子目录、`OVERFLOW`、周期核对、监控关闭、根目录不可访问和构建竞态测试。因此下一阶段不需要再次重做 revision、freshness 或发布协议。后续扩展必须建立在这些不变量之上，不能为了增加功能绕过状态机。

## 2. 没有被直接问到、但更关键的问题

### 谁唯一拥有知识库运行态的一致性

下一阶段真正的问题不是“还应该使用哪个设计模式”，而是以下状态由谁作为唯一写入者共同维护：

```text
knowledgeBaseId
sourceRevision
publishedIndexRevision
IndexStatus
FreshnessSnapshot
active IndexHandle
后台任务 identity
```

当前结构已经出现明确的职责聚集：

```text
MainFrame.java                         1291 行，创建 5 类 SwingWorker
SemanticSearchEngine.java               664 行，扫描/读取/分块/评分/高亮
KnowledgeService.java                   549 行，实现 7 个输入端口
AppRepository.java                      362 行，知识库/源/设置/freshness/发布 SQL
FileSystemSourceFreshnessMonitor.java   300 行，watcher/调度/session/reconciliation
```

行数本身不是判定标准。关键因果链是：

```text
继续加入增量索引、文档格式和诊断功能
  -> 更多异步路径需要修改 revision/status/freshness/handle
  -> 如果只按“层”机械拆类，状态写入会分散到多个 service
  -> 类虽然变小，但状态转换无法原子审计
  -> stale callback、错误 READY、幽灵片段和旧内容发送风险重新出现
```

因此下一步必须先确定运行态的一致性边界，再按变化原因拆分职责。三层架构、设计原则和设计模式都应服务于这个目标，而不是为了展示概念增加空壳接口或继承层级。

## 3. 实施顺序的因果关系

```text
运行态唯一所有者 + 明确状态转换策略
  -> 才能安全拆分 KnowledgeService 和异步 UI
  -> 才能在拆分后继续证明 revision/freshness/handle 一致

按变化原因拆分检索流水线
  -> 才能实现可替换的 reader/chunker/ranking policy
  -> 才能安全加入增量索引和更多文件格式

可重复的质量/性能基线
  -> 才能判断排序或 HNSW 是否真的变好

adapter 契约测试
  -> 才能证明不同实现满足相同的失败、取消和原子性语义
  -> 才是真正满足里氏替代原则，而不只是实现同一个 Java 接口
```

如果跳过结构稳定化直接加功能，表面功能会增加，但后续每个改动都会同时触碰 UI worker、应用状态、检索算法和 SQLite 事务，更难证明没有重新引入串库、旧数据发送或排名回退。

## 4. 第一阶段：运行期 freshness（已完成）

本阶段已经实现：

- `SourceFingerprint`、`SourceFreshnessMonitor`、`FreshnessReconciler` 和 `FreshnessGate`。
- 本地目录递归监听、新建子目录注册、debounce 和 `OVERFLOW` 完整核对。
- 周期性 reconciliation，处理未收到 watcher 事件的变化。
- 普通文件事件立即固定为 `CHANGED`，即使 size/mtime 相同也不会重新放行。
- 文件变化后条件标记 `DIRTY`、使活动 handle stale，并阻止远程 RAG。
- 构建前切换 monitor 基线，发布后以 manifest hash 启动新 session。
- monitor epoch 丢弃旧 session 的延迟回调。
- UI 显示 freshness 原因和最后核对时间。

已覆盖测试：

- 同会话修改文件后及时变为 `DIRTY`。
- 删除敏感文件后远程 HTTP 请求次数为 0。
- 新建子目录后继续监控其中的文件。
- `OVERFLOW` 和周期任务都会执行完整 reconciliation。
- watcher 关闭或根目录不可访问时保守拒绝。
- 构建期间的文件变化不能错误发布为 `READY`。
- 旧 monitor 回调不能污染新发布的 `READY`。

## 5. 第二阶段：降低继续扩展时的修改风险（已完成）

本阶段已经完成。实现保持现有 revision、freshness、原子发布和检索算法结果不变，并建立了可安全扩展的职责与一致性边界。

### 三层架构与依赖方向

课程中的三层架构映射为：

```text
表现层
  adapter.in.swing
       ↓
业务与应用层
  application + 状态转换策略 + retrieval pipeline
       ↓
数据访问与基础设施层
  adapter.out.sqlite/filesystem/onnx/openai/security
```

application ports 不是为了增加额外层级，而是用于落实依赖倒置：表现层和业务层依赖抽象，基础设施在 `AppCompositionRoot` 中注入。

### 先建立运行态唯一所有者

建议新增：

```text
ActiveKnowledgeContext
ActiveKnowledgeRuntime
IndexLifecycle
```

`ActiveKnowledgeContext` 是不可变运行态快照：

```text
knowledgeBaseId
sourceRevision
IndexStatus
FreshnessSnapshot
IndexHandle
```

`IndexLifecycle` 只表达允许的转换及其前置条件：

```text
restore / beginBuild / invalidate / publish / fail / cancel
```

`ActiveKnowledgeRuntime` 是运行态的唯一写入入口，负责原子替换 context。拆分后的用例不能分别保存或修改 `engine`、`activeKnowledgeBase`、`activeHandle` 和 monitor epoch。

不为每个 `IndexStatus` 强制创建一个状态类。当前使用枚举、不可变 context 和集中转换策略更容易测试和审计；只有当每个状态出现大量独立行为时再评估完整 State 模式。

### 拆分应用用例

按用例而不是按技术名拆分：

```text
KnowledgeBaseUseCases
KnowledgeSourceUseCases
IndexBuildUseCase
SearchUseCase
AskUseCase
ApiSettingsUseCase
DesktopQueryService
```

这些用例共享同一个 `ActiveKnowledgeRuntime` 和发布协议，但不互相调用具体实现类。

输入端口不能继续泄漏检索实现对象。新增应用 DTO：

```text
IndexBuildProgress
IndexBuildResult
KnowledgeBaseView
SearchResultView
CitationView
IndexStatusView
DocumentReference
```

`RebuildKnowledgeIndex` 不再返回 `SemanticSearchEngine.IndexReport`，`SearchKnowledge` 不再向 Swing 暴露 `DocumentChunk`。

### 拆分 Swing 与后台任务

Swing 拆出真正拥有控件状态的页面对象：

```text
KnowledgePanel + KnowledgeController
SearchPanel + SearchController
AskPanel + AskController
StatusBar
BackgroundTaskCoordinator
DesktopFileGateway
```

`MainFrame` 只保留窗口组合、页面导航和应用关闭流程。后台 worker 的创建、取消、identity 校验和 EDT 回调统一放入 `BackgroundTaskCoordinator`。

`BackgroundTaskCoordinator` 统一负责：

```text
创建与取消任务
捕获 knowledgeBaseId + sourceRevision
丢弃 stale result
EDT 回调
统一错误处理
```

这里使用 Command/Observer/Coordinator 的思想，但不建立复杂的命令继承树。UI 只消费 view model，通过 `DesktopFileGateway` 打开文件，不沿对象链访问 chunk、manifest 或 engine 内部状态。

### 拆分检索流水线

检索拆分顺序按变化原因进行：

```text
DocumentScanner
DocumentReaderRegistry
ChunkerRegistry
LexicalFeatureExtractor
QueryAnalyzer
LexicalScorer
SemanticScorer
RankingPolicy
SemanticHighlightService
```

不要一次性重写全部算法。先移动现有行为并保持 golden tests 不变，再逐个替换实现。

构建和查询分别组合为：

```text
DocumentScanner -> DocumentReader -> Chunker -> FeatureExtractor -> IndexBuilder
QueryAnalyzer -> LexicalScorer + SemanticScorer -> RankingPolicy -> SearchResultView
```

优先使用 Strategy、Registry 和对象组合，不建立扫描器/reader/chunker 的庞大继承体系。

### 拆分 SQLite adapter，但不拆散事务

接口可以拆为：

```text
KnowledgeBaseRepository
KnowledgeSourceRepository
IndexPublicationRepository
FreshnessRepository
SettingsRepository
```

实现可以是多个 SQLite adapter，并共享 `SqliteTransactionManager`。接口隔离不等于拆散事务；添加数据源、递增 revision 和标记 `DIRTY` 仍必须在同一个 SQLite 事务中完成。

### 面向对象设计原则的落点

| 原则 | 具体约束 |
| --- | --- |
| 单一职责原则 | 按变化原因拆分；页面布局、任务调度、状态转换、扫描、分块和评分分别演进 |
| 开放封闭原则 | reader、chunker、ranking policy 通过 Strategy + Registry 扩展 |
| 依赖倒置原则 | 用例只依赖 repository、embedder、chat model、monitor 等端口 |
| 里氏替代原则 | adapter 必须通过统一契约测试，证明失败、取消、原子性和保守拒绝语义一致 |
| 接口隔离原则 | 每个页面和用例只依赖所需的小接口与 view model |
| 合成复用原则 | 检索流水线组合小策略，不通过多层继承复用实现 |
| 迪米特法则 | Swing 不读取 `DocumentChunk`、manifest、engine 或 SQLite 内部对象 |

实现同一个 Java 接口不代表满足里氏替代原则。例如一个索引 repository 原子发布、另一个直接覆盖活动文件，两者在语义上不可替换。必须使用 contract tests 验证行为契约。

### 架构和契约验证

在现有 ArchUnit 基础上增加：

- Swing 不依赖 `DocumentChunk`、`SemanticSearchEngine`、`IndexHandle` 或任何 `adapter.out`。
- 用例实现之间不直接依赖，只通过 runtime、domain policy 和 ports 协作。
- 检索 reader/chunker/scorer 不反向依赖 Swing、SQLite 或 OpenAI。
- 只有 `IndexLifecycle`/`ActiveKnowledgeRuntime` 可以改变活动运行态。
- 每个 `DocumentReader`、`IndexRepository`、`ChatModel`、`SourceFreshnessMonitor` 实现运行同一套契约测试。
- 保留完整搜索 golden tests、freshness 竞态测试和原子发布测试。

### 验收标准

- `MainFrame` 不创建 worker，也不直接读取 `DocumentChunk`。
- `MainFrame` 只负责窗口组合、导航和关闭流程。
- 搜索、问答和知识库页面可以分别构造和测试。
- 所有活动运行态转换只能经过 `IndexLifecycle`/`ActiveKnowledgeRuntime`。
- input ports 和 Swing 不暴露 `SemanticSearchEngine` 类型。
- 修改分块策略不修改搜索评分代码。
- 修改排名权重不修改文件扫描或 embedding adapter。
- application tests 继续只使用内存 fake。
- adapter contract tests 证明替换实现不会改变失败和一致性语义。
- ArchUnit 明确检查新的依赖边界。
- 现有 25 个 JUnit/ArchUnit 测试、真实 ONNX 回归和模拟 OpenAI 回归保持通过。

## 6. 第三阶段：增量索引，而不是更频繁地全量重建（已完成）

### 为什么排在 freshness 和结构稳定化之后

增量索引依赖可靠地知道“哪些文件发生了变化”，还会同时触碰 reader、chunker、embedding、manifest 和发布协议。如果文件变化检测或职责边界本身不可靠，增量构建会比全量构建更容易留下幽灵片段、漏掉删除内容，或把算法优化与发布状态耦合在一起。

先完成结构稳定化后，增量索引可以作为独立的规划和构建策略加入，而不需要再次修改 Swing、问答流程或活动运行态所有权。

### 数据模型

为每个已发布索引记录文件级身份：

```text
relativePath
size
modifiedAt
contentHash
readerVersion
chunkingVersion
chunkIds
```

建议对象：

```text
FileFingerprint
DocumentIndexEntry
IncrementalIndexPlan
IncrementalIndexPlanner
IncrementalIndexBuilder
```

`IncrementalIndexPlanner` 应是无文件系统和数据库依赖的纯策略：输入上一 manifest 和当前 fingerprints，输出 `added/modified/deleted/reused`。这使规划逻辑可以用小型对象图和表驱动测试完整验证。

仅使用修改时间不够可靠。快速修改、文件复制和部分网络文件系统可能保留时间戳；最终身份需要内容 hash。可以先用 size/mtime 作为快速筛选，再只对候选文件计算 hash。

### 构建流程

```text
读取上一已发布 manifest
  -> 比较文件 fingerprint
  -> 复用未变化文件的 chunks/embeddings
  -> 只读取和向量化新增/修改文件
  -> 删除已不存在文件的 chunks
  -> 生成完整的新 revision snapshot
  -> 仍按现有原子流程发布
```

增量构建不能直接修改活动 snapshot。它只是更高效地生成一个完整的新 snapshot，发布不变量保持不变。

### 验收标准

- 只修改一个文件时，embedding 调用只包含该文件的新 chunks。
- 删除文件后，新 revision 中不存在其任何 chunk。
- reader/chunking/model 版本变化时自动退化为必要范围的全量重建。
- 增量构建失败仍保留上一 revision。
- 与全量重建结果进行等价性测试。

### 实施结果与证据

- `IndexSnapshot` 升级为格式 v4，通过 `DocumentIndexEntry` 记录 root、relativePath、size、modifiedAt、SHA-256、reader/chunker 版本和 chunk IDs。
- `IncrementalIndexPlanner` 是无文件系统、数据库和 embedding 依赖的纯策略，输出 `added/modified/deleted/reused`。
- `IncrementalIndexBuilder` 从上一已发布 snapshot 复用未变化 chunks/embeddings，只处理新增和修改文件，并始终生成完整的新 revision snapshot。
- embedding model signature、reader version 或 chunking version 不兼容时，复用门禁自动关闭或把对应文件归入 modified。
- watcher 确认外部内容变化时条件递增 `source_revision`；对已发布 revision 主动重建时也会先原子分配下一 revision，增量构建失败不会覆盖或删除旧 published revision 文件。
- `IncrementalIndexTest` 覆盖单文件 embedding、删除清理、版本回退和全量等价；`RuntimeFreshnessTest` 覆盖增量 embedding 失败时保留旧 revision 文件。
- 完整 `build-and-test.cmd` 验证包含 47 项 JUnit/ArchUnit、真实 ONNX 跨语言检索和模拟 OpenAI JSON/SSE 回归。

## 7. 第四阶段：先建立检索质量基线，再调算法（已完成）

### 更深一层的优化风险

当前可以证明功能工作，但还不能稳定回答“某次排序调整是否整体更好”。如果没有固定查询集和期望结果，调权重通常只会改善眼前示例，同时让其他查询回退。

### 实现内容

建立项目内离线评测集：

```text
query
language
expectedDocuments
expectedPassages
mustNotReturn
category
```

至少覆盖：

- 中问英、中英混合和代码标识符。
- 精确错误码、配置键和函数名。
- 语义相近但不应命中的负例。
- 删除或权限变化后的敏感内容负例。

记录指标：

```text
Recall@5
MRR@10
nDCG@10
首次查询延迟
缓存后延迟
索引耗时
每千 chunks 内存占用
```

`RankingPolicy.version` 与评测结果一起保存。权重调整必须提供同一数据集上的前后对比，不以少数手工查询作为依据。

### 可尝试的检索优化

在基线建立后再评估：

- 词法部分由自制 TF-IDF 升级为 BM25。
- query/document 分开预处理。
- 结果去重和同文件多片段合并。
- 对代码、配置和自然语言采用不同 ranking policy。
- 小型 cross-encoder reranker，仅对 Top-N 本地重排。

### 验收标准

- 每次排名修改都有可重复的基准报告。
- 关键查询的 Recall/MRR 不低于明确阈值。
- 性能回退超过预算时构建失败或需要显式批准。

### 实施结果与证据

- `examples/evaluation/retrieval-baseline.json` 固定 8 个查询，覆盖中问英、中英混合、代码标识符、精确配置键、跨语言界面描述、敏感内容负例、自然语言代码定位和符号定义定位，并在数据集中声明 Recall@5、MRR@10、nDCG@10 最低阈值。
- `RetrievalEvaluator` 统一计算 Recall@5、MRR@10、nDCG@10、首次/缓存查询延迟、索引耗时和每千 chunks 估算内存；逐查询结果同时检查 `mustNotReturn`。
- JSON 报告记录 dataset/version、`RankingPolicy.version`、指标、性能数据和逐查询排名，可用于同一数据集的前后对比。
- `RetrievalEvaluationMain` 位于 bootstrap，遵守 infrastructure 只由组装层创建的边界；`build-and-test.cmd` 在真实 ONNX 回归后运行质量门禁，低于阈值或出现禁止结果即失败。
- `RetrievalEvaluatorTest` 覆盖指标、禁止结果、版本记录、内存估算、阈值验证和 UTF-8 JSON 报告写入。

## 8. 第五阶段：文件格式扩展（已完成）

### 实施原则

文件格式扩展应通过 `DocumentReader` 注册，而不是在扫描器或评分器中继续增加条件分支。

建议顺序：

1. PDF 文本层，使用 PDFBox，不做 OCR。
2. DOCX/PPTX/XLSX，使用 Apache POI，并限制压缩包展开大小。
3. HTML 正文提取。
4. OCR 作为可选、较慢且独立的 adapter。

每个 reader 必须返回统一的：

```text
document identity
logical section/page
text
source location
reader version
warnings
```

### 必须处理的风险

- 加密 PDF 和损坏文档。
- Office zip bomb。
- 超大页数、超大图片和内存峰值。
- 提取文本为空但文件并非损坏。
- 页码/段落定位与引用打开方式。

### 验收标准

- 新格式失败只跳过该文档并记录原因，不破坏已发布索引。
- 引用能够定位到页码或逻辑 section。
- reader 版本变化会触发对应文件重新索引。
- 默认构建仍不依赖 OCR 或外部进程。

### 实施结果与证据

- 新增版本化 `DocumentReader` registry；scanner 通过 registry 判断支持格式和输入上限，不再维护扩展名条件分支。
- PDFBox 读取 PDF 文本层，拒绝加密/需要密码、无文本层、损坏、超过 1000 页或提取文本超过限制的文档；使用 mixed memory/temp-file 模式控制内存峰值。
- Apache POI 读取 DOCX/PPTX/XLSX，统一配置 OOXML zip bomb 防护；XLSX 使用 SAX/event reader，并限制工作表、行、单元格和文本量。
- jsoup 提取 HTML main/article 正文，排除 script/style/nav/footer/aside/form，不访问外部资源。
- `ReadDocument -> DocumentSection -> DocumentTextUnit` 统一 document identity、页/section、source location、reader 版本和 warning；搜索、引用 UI 和远程 RAG prompt 使用相同位置标签。
- 索引格式升级为 v5，`DocumentIndexEntry` 保存每个文件的 `readerId + readerVersion`；reader 版本变化只重建对应格式。
- 损坏、过大、无法访问或空文本文档生成结构化 `IndexBuildWarning`，其余文件继续构建；发布仍遵守完整 snapshot、原子移动和 SQLite 条件发布。
- `DocumentReaderTest` 覆盖 PDF、加密 PDF、DOCX、PPTX、XLSX、HTML 和损坏文档隔离；`DocumentScannerTest` 覆盖大小限制原因；`IncrementalIndexTest` 覆盖按 reader 版本定向重建。
- 默认构建没有 OCR、LibreOffice 或其他外部进程依赖。

## 9. 第六阶段：规模与性能（已完成）

不要因为“向量数据库更先进”就提前替换当前线性扫描。先用基准确认瓶颈属于搜索、embedding、文件读取还是 UI 渲染。

当以下任一条件出现时再评估 ANN：

- 片段规模超过约 10 万且查询延迟持续超出目标。
- 内存扫描成为 profile 中的主要耗时。
- 质量基线证明近似搜索的召回损失可接受。

候选方案优先考虑本地、可版本化、可原子发布的 HNSW 实现。ANN 文件必须与同一 `IndexManifest` 和 revision 绑定，不能成为第四份无身份状态。

其他性能工作按 profile 决定：

- embedding 批次大小和线程数。
- 文件读取并发与磁盘背压。
- snapshot 序列化格式和启动时间。
- UI 大结果集分页或虚拟化。
- 模型文件签名缓存，避免重复计算大文件 hash。

### 验收标准

- 给出固定硬件和固定数据集上的前后数据。
- 性能优化不降低 freshness、发布原子性和检索质量阈值。
- 新索引格式具备迁移或明确重建策略。

### 实施结果与证据

- 保留精确线性扫描，没有在 10 万 chunks 阈值和 profile 证据出现前引入 ANN；索引格式仍为 v5，不新增无 manifest 身份的旁路状态。
- `IncrementalIndexBuilder` 对 scan、read/chunk、embedding、assemble 和 total 分阶段计时，构建完成诊断事件记录文件数、chunk 数与阶段耗时，便于先确认瓶颈再优化。
- 新增持久化 `ModelFileSignatureCache`：以规范路径、文件大小、mtime 和 filesystem identity 校验缓存，模型元数据变化即重算 SHA-256；缓存文件使用临时文件和原子移动发布。
- 新增 `PerformanceBenchmarkMain`，固定记录数据集路径/签名、文件数、Windows/Java/CPU/堆信息、优化前完整 hash 与优化后缓存命中的耗时及签名等价性；`build-and-test.cmd` 在质量门禁后生成报告。
- 2026-07-12 固定 `examples/knowledge`（SHA-256 `a4281c26...8e18de`）、Windows 11、Java 17.0.8、16 logical processors、约 3.95 GiB max heap：完整 hash 115.5649 ms，缓存命中 23.7596 ms，4.86x，签名完全一致。
- 扩充后的 8-query 数据集真实 ONNX 回归：Recall@5 1.000、MRR@10 1.000、nDCG@10 0.991；cold query 8.762 ms、cached query 1.437 ms，未降低质量门槛、freshness 或原子发布保证。
- `ModelFileSignatureCacheTest` 覆盖稳定缓存和模型文件变化失效；报告写入 `target/performance/performance-report.json`。

## 10. 第七阶段：安全与可运维性（已完成）

### 安全

- Windows Credential Manager adapter，替代应用派生密钥作为首选方案。
- API host allowlist/确认提示，避免误把片段发送到意外地址。
- 每次远程问答显示即将发送的知识库、片段数量和目标 host。
- 可选的“仅本地 RAG”知识库策略。
- 日志中禁止记录 API Key、完整 prompt 和敏感片段。

### 可诊断性

增加本地诊断事件，但不引入复杂遥测平台：

```text
index build started/completed/failed/cancelled
freshness changed
stale task discarded
vector fallback reason
RAG blocked reason
adapter latency and error category
```

UI 增加“诊断信息”入口，展示当前 revision、发布 revision、状态、manifest 摘要、最近错误和 freshness 最近核对时间。

### 验收标准

- 用户能解释“为什么不能问答/为什么降级为词法”。
- 支持导出不含密钥和正文的诊断报告。
- 远程发送目标和数据范围在操作前可见。

### 实施结果与证据

- `WindowsCredentialManagerSecretStore` 通过 Windows `CredWriteW/CredReadW` 保存 Generic Credential，SQLite 只保存 credential marker；非 Windows 或原生调用失败时才兼容原应用级 AES-GCM 存量格式，并记录不含密钥的 fallback 事件。
- `ApiConfig` 仅接受无内嵌 user-info 的 HTTP(S) URL，并提取规范化 host；设置保存受信任 host allowlist。
- 每次远程问答在 HTTP 前显示知识库名称、revision、目标 host、准确片段数量和文件/位置范围；用户可仅本次允许、信任 host 后允许或取消。确认仍每次显示，allowlist 只改变风险提示。
- 每个知识库可启用“仅本地 RAG”；`AskUseCase` 在检索/HTTP 边界阻止远程发送并给出明确原因。
- 新增容量受限的 `InMemoryDiagnosticLog`，覆盖 index build started/completed/failed/cancelled、freshness changed、stale task discarded、vector fallback reason、RAG blocked reason 与 adapter latency/error category；事件只含身份、状态、host、数量、耗时和错误类别。
- UI 新增“诊断信息”页，展示 current/published revision、index/freshness 状态、manifest 摘要和最近事件；可导出 UTF-8 JSON，报告对象不可访问 API key、conversation、prompt 或 chunk content。
- `InMemoryDiagnosticLogTest` 验证容量边界与 Bearer/Authorization 脱敏，`ApiConfigSecurityTest` 验证 host 和 URL 边界；ArchUnit 继续保证 Swing 不依赖检索内部对象。

## 11. 工程论证与课程交付材料

项目不仅需要“可以运行”，还应能够解释问题、论证设计选择并证明实现符合设计。每个后续迭代同步维护：

### 架构决策记录

至少记录以下 ADR：

```text
为什么选择单 Maven 模块的模块化单体
为什么使用三层架构 + ports/adapters 落实依赖倒置
为什么活动运行态只有一个写入所有者
为什么增量构建仍发布完整 revision snapshot
为什么 watcher 只使索引失效而不直接发布
为什么 reader/chunker/ranking 使用组合与 Strategy
```

### 需求追踪矩阵

对每个关键需求记录：

```text
工程问题
  -> 因果机制
  -> 设计原则/模式
  -> 实现类或接口
  -> 自动化测试
  -> 验收证据
```

不能用“使用了某模式”作为合理性结论。合理性必须说明该模式隔离了哪个变化、避免了哪个故障，以及测试如何证明。

### 必须保留的模型与报告

- 三层架构与依赖方向图。
- 索引状态转换表。
- 构建、freshness、搜索和远程问答时序图。
- adapter 契约测试报告。
- 检索质量与性能基准报告。
- 不包含 API Key、完整 prompt 和敏感正文的诊断样例。

## 12. 已完成的结构稳定化迭代

该迭代完成第二阶段的结构稳定化，当时没有同时加入 PDF、增量索引、BM25 或 HNSW：

1. 已为搜索、构建、问答、freshness、运行态、页面和失败路径补充 characterization/golden tests。
2. 已定义 `ActiveKnowledgeContext`、`ActiveKnowledgeRuntime` 和 `IndexLifecycle`，集中运行态转换。
3. 已用 application DTO 替换 input ports 中的 `SemanticSearchEngine.IndexReport` 和 `DocumentChunk`。
4. 已提供知识库、数据源、构建、搜索、问答、设置和查询用例；桌面生产组合中的搜索、问答和设置直接装配独立实现。
5. 已新增 `BackgroundTaskCoordinator`，迁移所有 `SwingWorker`、取消、identity 检查和 EDT 回调。
6. 已拆出 `KnowledgePanel`、`SearchPanel`、`AskPanel` 和 `StatusBar`，并分别通过独立构造测试。
7. 已按扫描、读取、分块、特征、分析、评分、排序和高亮拆分检索流水线，golden/真实 ONNX 结果保持通过。
8. 已拆分 SQLite repository ports，并使用 `SqliteTransactionManager` 保留跨表事务。
9. 已增加 ArchUnit 细粒度依赖规则；filesystem monitor、index repository、SQLite transaction、OpenAI JSON/SSE 和 reader/scanner 行为由 adapter/回归测试覆盖。
10. 已更新 README、DEVELOPMENT、6 个 ADR、架构/状态/时序说明和需求追踪矩阵。

完成证据：`MainFrame` 从 1291 行降至 115 行，`SemanticSearchEngine` 从 664 行降至 301 行；`MainFrame` 只保留窗口组合、导航和关闭，页面工作流由 `DesktopWorkspaceController` 协调；活动运行态只有 `ActiveKnowledgeRuntime` 写入，Swing 不读取检索内部对象；该阶段当时的 40 项 JUnit/ArchUnit、真实 ONNX 和模拟 OpenAI JSON/SSE 完整回归全部通过。

## 13. 暂不优先

在结构稳定化和质量基线完成前，不优先进行：

- OCR。
- 远程向量数据库。
- 多进程并发写索引。
- 可配置的大量排名参数。
- 全面替换 UI 技术栈。
- 为了目录整齐拆分多个 Maven module。

这些功能不是永远不做，而是当前无法解决最关键的错误风险，也不能提高后续优化结果的可验证性。
