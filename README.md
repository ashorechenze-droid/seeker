# SimpleRAG

SimpleRAG 是一个 Java 17 + Swing 桌面端本地知识库客户端，支持多知识库、中英文文档与代码检索、语义片段高亮，以及基于 OpenAI 兼容 API 的带引用、多轮 RAG 问答（聊天气泡 UI）。

文件扫描、分块、TF-IDF 特征、ONNX embedding 和索引持久化均在本机完成。语义模型通过 LangChain4j 的 in-process ONNX Runtime 在 JVM 内运行，不依赖 Python，也不会把文档上传到 embedding 服务。

## 环境要求

- Windows 10/11
- JDK 17 或更高版本
- Maven 3.9 或更高版本

项目通过 `.mvn/maven.config` 把 Maven 本地仓库固定到项目内的 `.mvn/repository`。

## 快速开始

首次使用先安装约 122 MB 的多语言模型：

```powershell
.\setup-semantic-model.cmd
```

脚本用纯 Java 下载器从 `HF_ENDPOINT` 指定的地址下载；未设置时默认使用 `https://hf-mirror.com`。模型保存到：

```text
D:\SimpleRAG\models\multilingual-minilm
```

启动客户端：

```powershell
.\run-client.cmd
```

或手动构建运行：

```powershell
mvn.cmd -q package
& "$env:JAVA_HOME\bin\java.exe" -jar target\SimpleRAG-1.0-SNAPSHOT.jar
```

## 使用流程

### 1. 管理知识库和数据源

左侧区域可以新建、编辑、删除和切换知识库，并为每个知识库添加多个本地目录。各知识库的数据源、revision、状态和索引完全独立。删除知识库不会删除源文件。

添加或移除数据源后，数据库会在同一事务中递增 `source_revision`，索引立即变为 `DIRTY`。界面随后可以重建索引；在重建完成前，系统不会把旧索引伪装成最新版本。

应用运行期间会为当前知识库递归注册文件监听器，并用周期性完整 reconciliation 补偿 Windows watcher 溢出、休眠恢复和不可靠文件系统事件。源文件被创建、修改或删除后，无需重启应用，`source_revision` 会条件递增，活动索引及时变为 `DIRTY`。新建子目录也会自动继续注册监听。

底部状态栏显示变化原因和最近一次源文件核对时间。监听器不可用、根目录不可访问、事件溢出尚未核对完成或 freshness 状态未知时，系统采用保守策略，不允许远程 RAG。

### 2. 重建索引

重建会捕获不可变的：

```text
knowledgeBaseId + sourceRevision + sourceSetHash + embeddingModelSignature
```

扫描、分块和向量化在独立 builder 中完成，不直接修改当前已发布索引。索引格式 v5 为每个文件保存相对路径、大小、修改时间、SHA-256、实际 `readerId + readerVersion`、chunker 版本和 chunk IDs。重建时会读取上一已发布 snapshot：未变化文件直接复用 chunks 和 embeddings，只读取、分块并向量化新增或修改文件；删除文件的 chunks 不会进入新 snapshot。模型或 chunker 不兼容时会扩大重建范围；单个 reader 版本变化时只重建该 reader 负责的格式。

损坏、加密、过大、无法访问或没有可提取文本的文档只会被跳过并记录逐文件原因，其他文档仍继续生成完整 snapshot。完成后界面显示跳过数量和 reader 警告；这些警告不会改变原子发布和 freshness 规则。

增量构建仍生成完整的新 revision snapshot。如果当前 revision 已经发布（例如用户主动重建 `READY` 索引或模型版本变化），开始构建时会先分配新的 revision，避免覆盖数据库仍引用的文件。构建完成后先写入：

```text
%USERPROFILE%\.simplerag\indexes\<知识库ID>\<revision>.bin.tmp
```

文件完整关闭后通过原子移动发布为 `<revision>.bin`，然后在 SQLite 事务中校验 `source_revision` 并更新发布指针。只有全部成功，内存中的活动索引才会切换到新版本。

如果构建失败、取消、应用退出或构建期间数据源再次变化：

- 当前已发布 revision 保持不变；
- 半成品不会成为活动索引；
- `.tmp` 文件会被清理；
- 状态显示为 `DIRTY` 或 `FAILED`；
- 远程 RAG 默认被禁止。

### 3. 语义检索

在“语义检索”页输入中文、英文、代码标识符或自然语言问题，可按扩展名过滤结果。结果保留绝对路径和统一来源位置：文本/源码显示行号，PDF 显示页码，DOCX/HTML 显示逻辑章节，PPTX 显示幻灯片，XLSX 显示工作表与行号。

- 黄色：原词命中。
- 绿色：二阶段向量定位得到的语义相关句子或代码段。

混合排序默认使用 78% 向量语义分数和 22% 词法分数。向量评分只有在下列条件全部满足时才启用：

- 索引包含 embedding；
- embedding provider 可用；
- 模型文件签名、模型名和预处理版本匹配；
- 查询向量与所有文档向量维度一致；
- 索引 manifest 完整且格式受支持。

任何条件不满足时都会降级为词法检索，不会对不兼容向量执行余弦计算。语义高亮与搜索使用同一兼容性判断。

### 4. RAG 知识问答

“知识问答”页可以配置 OpenAI 兼容服务：

```text
OpenAI: https://api.openai.com/v1
Ollama: http://localhost:11434/v1
其他服务: https://example.com/v1
```

客户端调用：

```text
GET  <baseUrl>/models
POST <baseUrl>/chat/completions
```

问答时最多召回 6 个片段，发送问题、片段路径、页码/章节/行号等来源位置和内容，并要求模型用 `[1]`、`[2]` 标注引用。回答支持 SSE 流式显示和随时停止；服务不支持 SSE 时自动回退到非流式响应。

知识问答页采用聊天气泡 UI（用户右对齐 / 助手左对齐），支持多轮追问：

- 多轮历史由内存模块 `application.conversation` 管理，绑定当前 `knowledgeBaseId + sourceRevision`。
- 历史只保留 user/assistant 文本，不保留引用片段；每一轮都会重新检索并重新做 freshness 检查。
- 默认最多保留约 12 轮、约 3000 token（粗估 chars/4）上下文；失败或取消的轮次不写入历史。
- Enter 发送、Shift+Enter 换行，可清空对话；右侧展示「本轮引用」，双击打开源文件。
- 助手多行回答会按聊天区域宽度铺开，短消息按内容收缩，避免气泡之间出现大片留白。
- 会话仅保存在内存，应用退出后不会恢复（SQLite 持久化尚未接入）。

远程 RAG 只允许使用 `READY`、`published_index_revision == source_revision` 且运行期 freshness 已被证明的索引。系统在召回前和实际 HTTP 发送前各执行一次 freshness gate；`DIRTY`、`BUILDING`、`FAILED`、`INCOMPATIBLE`、监控中断或核对状态未知时，请求会在任何远程调用前被拒绝。

安全边界：

- 本地检索不会上传文档。
- API URL 必须是无内嵌账号密码的 HTTP(S) 地址。远程 RAG 不会发送整个知识库。
- 每次 HTTP 请求前都会显示知识库、revision、目标 host、准确片段数量和文件/页码/章节/行号范围；可仅本次允许、信任 host 后允许或取消。
- 每个知识库可勾选“仅本地 RAG”，该策略在 application use case 内阻止所有远程问答。
- 敏感资料应使用本地兼容模型服务。
- Windows 上 API Key 首选保存为当前用户的 Windows Credential Manager Generic Credential，SQLite 只保存 marker；原 AES-GCM 格式仅用于存量兼容或 Credential Manager 不可用时的 fallback。

## 诊断与性能报告

顶部“诊断信息”页显示当前/发布 revision、索引状态、freshness 状态和最近核对时间、manifest 摘要，以及最近的构建、降级、阻止和 adapter 延迟事件。可导出 UTF-8 JSON；报告不读取或输出 API Key、完整 prompt、对话历史或 chunk 正文。

索引构建记录 scan、read/chunk、embedding、assemble 和 total 分阶段耗时。模型文件 SHA-256 使用持久化签名缓存，文件规范路径、大小、mtime 或 filesystem identity 变化时自动失效，缓存通过临时文件和原子移动更新。

单独生成固定数据集/硬件性能报告：

```powershell
mvn.cmd -q -DskipTests package
& "$env:JAVA_HOME\bin\java.exe" -cp "target\SimpleRAG-1.0-SNAPSHOT.jar" `
  com.simplerag.bootstrap.PerformanceBenchmarkMain
```

报告写入 `target/performance/performance-report.json`。2026-07-12 在 Windows 11、Java 17.0.8、16 logical processors、固定 `examples/knowledge` 数据集上，模型签名完整 hash 为 115.5649 ms，缓存命中为 23.7596 ms（4.86x），且签名等价。

## 索引状态

| 状态 | 含义 | 向量检索 | 远程 RAG |
| --- | --- | --- | --- |
| `EMPTY` | 从未建立索引 | 否 | 否 |
| `READY` | 已发布索引与当前 revision 一致，且 watcher/reconciliation 可证明源文件未变化 | 模型兼容时允许 | freshness gate 通过时允许 |
| `DIRTY` | 数据源或外部文件已变化 | 否，词法模式可用 | 否 |
| `BUILDING` | 正在构建指定 revision | 当前界面不发布新结果 | 否 |
| `FAILED` | 最近一次构建失败，旧发布版本仍保留 | 否，词法模式可用 | 否 |
| `INCOMPATIBLE` | 旧索引、模型或格式不兼容 | 否 | 否 |

应用启动时会清理中断构建留下的 `.tmp` 和未被数据库引用的 revision 文件。旧版缺少完整 manifest 的索引不会被静默当作兼容向量索引，而是要求重建。

watcher 只负责使旧索引失效，不会直接修改或发布索引。用户点击重建后仍走临时文件、原子移动和 SQLite 条件发布流程；构建期间发生的文件事件会使发布条件失败，不能把较新的 `DIRTY` 错误覆盖成 `READY`。

## 支持的内容

- Markdown、纯文本、配置文件和常见编程语言源码。
- PDF 文本层（PDFBox，不做 OCR）。
- DOCX、PPTX、XLSX（Apache POI；XLSX 使用流式 SAX reader）。
- HTML/HTM 正文（jsoup，优先 main/article 并排除脚本和导航噪声）。
- 代码重叠窗口分块与文档自然段分块。
- camelCase、snake_case、中文 n-gram、TF-IDF 和少量概念归一。
- 384 维多语言句向量、混合排序和句子级语义定位。
- 自动跳过 `.git`、`node_modules`、`target`、`build`、虚拟环境等目录。

输入限制按 reader 设置：纯文本 2 MiB、HTML 5 MiB、PDF/OOXML 32 MiB，并另外限制 PDF 页数、Office zip 展开、工作表/行/单元格数量和提取文本量。当前不解析图片、扫描件、纯图片 PDF、旧版 DOC/PPT/XLS、宏或嵌入对象，也不启动 OCR 或外部进程。

## 数据存储

| 数据 | 位置 |
| --- | --- |
| 多语言 ONNX 模型 | `D:\SimpleRAG\models\multilingual-minilm` |
| Maven 项目依赖 | `D:\SimpleRAG\.mvn\repository` |
| SQLite 数据库 | `%USERPROFILE%\.simplerag\simplerag.db` |
| 版本化索引 | `%USERPROFILE%\.simplerag\indexes\<知识库ID>\<revision>.bin` |
| 可执行 JAR | `D:\SimpleRAG\target\SimpleRAG-1.0-SNAPSHOT.jar` |

SQLite 使用版本化 migration。`knowledge_base` 保存 `source_revision`、`published_index_revision`、`index_status`、最近错误、`last_verified_source_hash`、`last_verified_at` 和 freshness 变化原因；`knowledge_index` 保存每个已发布 revision 的 manifest 元数据。

## 架构

```text
adapter.in.swing
  MainFrame（窗口组合、导航、关闭）
  DesktopWorkspaceController（页面工作流）
  KnowledgePanel / SearchPanel / AskPanel / StatusBar
  BackgroundTaskCoordinator / DesktopFileGateway
  KnowledgeController / SearchController / AskController
                |
                v
application.port.in -> 独立 use cases + application DTO
application.conversation（内存多轮会话）
                |
                v
ActiveKnowledgeRuntime + IndexLifecycle
                |
                v
application.port.out
  KnowledgeBaseRepository / KnowledgeSourceRepository
  IndexPublicationRepository / FreshnessRepository / IndexRepository
  ChatModel / SecretStore / SettingsRepository
                ^
                |
adapter.out.sqlite / filesystem / onnx / openai / security

bootstrap.AppCompositionRoot
  唯一主要的具体依赖组装位置
```

`ActiveKnowledgeRuntime` 原子持有当前知识库、source revision、状态、freshness 和 `IndexHandle`，所有转换通过 `IndexLifecycle`。搜索、问答和后台 UI 结果都绑定 `knowledgeBaseId + sourceRevision`；切换知识库或 revision 变化后，`BackgroundTaskCoordinator` 会丢弃旧任务结果。

检索构建与查询由以下对象组合，修改分块不会触碰评分，修改排名也不会触碰扫描或 embedding adapter：

```text
DocumentScanner -> FileFingerprint -> IncrementalIndexPlanner
  -> DocumentReaderRegistry -> ChunkerRegistry
  -> IncrementalIndexBuilder -> 完整 snapshot

LexicalFeatureExtractor -> Index state

QueryAnalyzer -> LexicalScorer + SemanticScorer
  -> RankingPolicy -> SearchResultView

SemanticHighlightService
```

SQLite output ports 已按知识库、数据源、发布、freshness 和设置拆分，但共享 `SqliteTransactionManager`，因此接口隔离不会拆散跨表事务。

架构决策见 [docs/adr](docs/adr)，结构稳定化与增量索引的需求、实现和测试映射见 [需求追踪矩阵](docs/REQUIREMENTS_TRACEABILITY.md)。

## 构建与测试

运行完整验证：

```powershell
.\build-and-test.cmd
```

脚本依次运行：

- JUnit 5 运行态生命周期、页面独立构造、多轮对话模块、事务、一致性、迁移、失败、取消、隐私和过期任务测试；
- 递归 watcher、动态子目录、`OVERFLOW` 完整核对和周期 reconciliation 测试；
- 同会话修改/删除文件、监控关闭时远程请求数为 0，以及构建期间文件变化的竞态测试；
- 增量 planner 表驱动测试、单文件 embedding 复用、删除清理、模型/reader/chunker 版本回退和全量等价性测试；
- 增量 embedding 失败后旧 published revision 与旧索引文件保留测试；
- PDF/HTML/OOXML reader、加密/损坏文档、页码/section 引用、reader 大小限制和按格式版本增量重建测试；
- 固定查询集上的 Recall@5、MRR@10、nDCG@10 与 `mustNotReturn` 真实 ONNX 质量门禁，并生成包含排名策略版本和性能数据的 JSON 报告；
- 模型签名缓存前后基准，记录固定数据集签名、硬件/JVM、耗时、加速比和签名等价性；
- Windows Credential Manager、URL/host 边界、诊断容量与敏感 header 脱敏测试；
- ArchUnit 三层依赖、Swing DTO 边界、检索流水线和后台任务所有权检查；
- 原有 `SemanticSearchEngineTest` 真实 ONNX 跨语言检索入口；
- 原有 `CourseFeaturesTest` SQLite、模拟 OpenAI JSON/SSE 和引用入口。

也可以只运行快速 JUnit/ArchUnit 测试：

```powershell
mvn.cmd -q test
```

完整开发说明见 [DEVELOPMENT.md](DEVELOPMENT.md)。

单独运行检索质量评测（默认读取 `examples/evaluation/retrieval-baseline.json`）：

```powershell
mvn.cmd -q -DskipTests package
& "$env:JAVA_HOME\bin\java.exe" -cp "target\SimpleRAG-1.0-SNAPSHOT.jar" `
  com.simplerag.bootstrap.RetrievalEvaluationMain
```

报告输出到 `target/evaluation/retrieval-report.json`。当前门槛为 Recall@5 ≥ 0.95、MRR@10 ≥ 0.95、nDCG@10 ≥ 0.90，并要求禁止文档不进入前 10；报告同时记录首次/缓存查询延迟、索引耗时、每千 chunks 内存估算和 `RankingPolicy.version`。
