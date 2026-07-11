# SimpleRAG 下一阶段扩展与优化计划

## 1. 当前基线

上一轮一致性改造已经完成，当前系统具备：

- `source_revision`、`published_index_revision` 和明确的 `IndexStatus`。
- 带完整 manifest 的 revision 索引文件。
- 模型文件签名、预处理版本和向量维度兼容检查。
- 独立 builder、临时文件、原子移动和 SQLite 条件发布。
- 构建失败、取消和启动恢复时保留上一已发布版本。
- 搜索、问答和 Swing 后台任务绑定 `knowledgeBaseId + sourceRevision`。
- 非 `READY` 状态禁止远程 RAG。
- application ports、基础设施 adapters、Swing controllers 和 `AppCompositionRoot`。
- JUnit 5、ArchUnit、真实 ONNX 回归测试和模拟 OpenAI JSON/SSE 测试。

因此下一阶段不需要再次重做 revision 或发布协议。后续扩展必须建立在这些不变量之上，不能为了增加功能绕过状态机。

## 2. 没有被直接问到、但更关键的问题

### 运行期间的 freshness 仍有时间窗口

目前 `sourceSetHash` 在以下时机计算：

1. 开始构建时。
2. 构建完成、发布前。
3. 应用启动并恢复索引时。

应用运行期间没有文件监听或周期性 reconciliation。由此产生以下因果链：

```text
索引进入 READY
  -> 用户在应用外修改或删除源文件
  -> SQLite source_revision 没有变化
  -> 内存 IndexHandle 仍为 READY
  -> requireReadyHandle 只看到 revision/status 匹配
  -> 旧索引片段仍可能被发送给远程 RAG
```

这不是普通的“索引更新不及时”，而是正确性和隐私边界之间的缺口。尤其当用户删除敏感文件并认为内容已经不再可用时，旧片段仍留在已发布索引中。

因此，下一步最高优先级不是 PDF、OCR、HNSW 或界面美化，而是让源文件变化能够在同一应用会话内使索引失效，并在远程调用前再次证明 freshness。

## 3. 实施顺序的因果关系

```text
运行期 freshness
  -> 才能安全做增量索引
  -> 才能安全扩展更多文件格式

可重复的质量/性能基线
  -> 才能判断排序或 HNSW 是否真的变好

拆分当前热点类
  -> 降低后续文件格式、增量索引和 UI 功能互相干扰的概率
```

如果跳过这些前置条件直接加功能，表面功能会增加，但每次修改都更难证明没有重新引入串库、旧数据发送或排名回退。

## 4. 第一阶段：关闭运行期 freshness 缺口

### 为什么先做

现有状态机只能感知应用内的数据源配置变化，不能及时感知应用外的文件变化。远程 RAG 的安全判断因此依赖一个可能已经过时的 `READY`。

### 实现内容

新增 `SourceFreshnessMonitor`，组合两种机制：

- `WatchService`：低延迟接收创建、修改和删除事件。
- 周期性 reconciliation：处理 `OVERFLOW`、网络盘、不可靠文件系统和应用休眠后丢失的事件。

不能只使用 `WatchService`。Windows 上需要为每个子目录注册 watcher，新建目录后也要继续注册；事件队列溢出时必须回退为完整 fingerprint 核对。

建议新增：

```text
SourceFingerprint
SourceFreshnessMonitor
FreshnessReconciler
FreshnessGate
```

数据库增加可选字段或独立表，记录最近一次已确认的源状态：

```text
knowledge_base.last_verified_source_hash
knowledge_base.last_verified_at
```

状态变化流程：

```text
文件事件或 reconciliation 发现变化
  -> 条件更新当前知识库为 DIRTY
  -> 使活动 IndexHandle 失效
  -> UI 立即显示待重建
  -> 取消或拒绝尚未发送的远程 RAG
```

RAG 调用前的 `FreshnessGate` 不应每次完整遍历所有文件，否则大型知识库的每次提问都会产生明显延迟。应使用 watcher generation + 最近 reconciliation 结果；发生 `OVERFLOW`、监控中断或状态未知时采用保守策略，先拒绝远程调用并要求核对。

### 必须新增的测试

- `READY` 后修改文件，不重启应用，状态在限定时间内变为 `DIRTY`。
- `READY` 后删除敏感文件，远程 HTTP 请求次数保持为 0。
- 新建子目录后，其中的文件变化仍能被监控。
- 模拟 `OVERFLOW` 后执行完整 reconciliation。
- watcher 关闭或根目录不可访问时，远程 RAG 采用保守拒绝策略。
- 构建期间产生的文件事件不会把较新的状态错误覆盖为 `READY`。

### 验收标准

- 本地文件变化不需要重启应用即可使索引失效。
- freshness 无法证明时不发送远程 RAG。
- watcher 不直接发布索引，只负责使现有索引失效；重建仍走原子发布流程。

## 5. 第二阶段：降低继续扩展时的修改风险

### 当前热点

当前主要类规模：

```text
MainFrame.java              1268 行
SemanticSearchEngine.java    664 行
KnowledgeService.java        444 行
AppRepository.java           307 行
```

行数本身不是问题。真正的问题是这些类同时持有多组会一起变化的状态：

- `MainFrame` 同时管理知识库、搜索、预览、API 配置、问答和多个 worker。
- `SemanticSearchEngine` 同时负责扫描、读取、分块、词法特征、查询分析、排名和高亮。
- `KnowledgeService` 同时编排知识库、构建、搜索、设置和问答。

继续加入文件监听、增量索引和 PDF 后，同一个改动会跨越更多状态，异步竞态也更难测试。

### 实现内容

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

UI 使用 view model，不继续向下读取检索内部对象：

```text
KnowledgeBaseView
SearchResultView
CitationView
IndexStatusView
```

检索拆分顺序按变化原因进行：

```text
DocumentScanner / DocumentReader
ChunkerRegistry
LexicalFeatureExtractor
QueryAnalyzer
LexicalScorer
SemanticScorer
RankingPolicy
SemanticHighlightService
```

不要一次性重写全部算法。先移动现有行为并保持 golden tests 不变，再逐个替换实现。

应用用例拆分为小的实现类，但共享同一套 repository 和发布协议：

```text
KnowledgeBaseUseCases
IndexBuildUseCase
SearchUseCase
AskUseCase
ApiSettingsUseCase
```

SQLite adapter 拆开知识库状态、设置和索引发布记录的 SQL，避免修改 API 设置时触碰索引事务代码。

### 验收标准

- `MainFrame` 不创建 worker，也不直接读取 `DocumentChunk`。
- 搜索、问答和知识库页面可以分别构造和测试。
- 修改分块策略不修改搜索评分代码。
- 修改排名权重不修改文件扫描或 embedding adapter。
- application tests 继续只使用内存 fake。
- ArchUnit 明确检查新的依赖边界。

## 6. 第三阶段：增量索引，而不是更频繁地全量重建

### 为什么排在 freshness 之后

增量索引依赖可靠地知道“哪些文件发生了变化”。如果文件变化检测本身不可靠，增量构建会比全量构建更容易留下幽灵片段或漏掉删除内容。

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

## 7. 第四阶段：先建立检索质量基线，再调算法

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

## 8. 第五阶段：文件格式扩展

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

## 9. 第六阶段：规模与性能

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

## 10. 第七阶段：安全与可运维性

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

## 11. 推荐的近期迭代

下一次开发迭代只做第一阶段，不同时加入 PDF 或 HNSW：

1. 定义 `SourceFreshnessMonitor` 与 `FreshnessGate` 接口和状态语义。
2. 为本地目录实现递归 `WatchService` 注册、debounce 和 `OVERFLOW` 处理。
3. 增加周期性 reconciliation。
4. 文件变化后条件标记 `DIRTY` 并使活动 handle 失效。
5. 在远程 RAG 前接入 freshness gate。
6. 补充同会话删除敏感文件后 HTTP 请求为 0 的测试。
7. UI 显示变化原因和最后核对时间。
8. 更新 README/DEVELOPMENT 的实时 freshness 行为。

本迭代的完成定义：用户在应用外修改或删除源文件后，无需重启，系统会及时停止使用旧索引进行远程 RAG，并明确告诉用户为什么需要重建。

## 12. 暂不优先

在运行期 freshness 和质量基线完成前，不优先进行：

- OCR。
- 远程向量数据库。
- 多进程并发写索引。
- 可配置的大量排名参数。
- 全面替换 UI 技术栈。
- 为了目录整齐拆分多个 Maven module。

这些功能不是永远不做，而是当前无法解决最关键的错误风险，也不能提高后续优化结果的可验证性。
