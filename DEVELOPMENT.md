# SimpleRAG 开发与架构说明

## 1. 目标与约束

SimpleRAG 的核心目标是在个人电脑上提供可用的中英文文档/代码语义检索，并允许用户选择 OpenAI 兼容服务完成带引用问答。

当前实现遵守以下边界：

- 文档扫描、分块、embedding 和检索默认在本机完成。
- Java 17 + Swing，单 Maven 模块，不引入 Spring 或依赖注入框架。
- ONNX 推理由 LangChain4j 在 JVM 进程内完成，不需要 Python。
- 远程 RAG 只发送召回片段，并且只允许使用明确为最新的索引。
- SQLite、内存索引和磁盘索引必须通过 revision 与 manifest 建立可验证关系。

## 2. 为什么进行了 revision/状态改造

旧实现把同一知识库的状态分散在三处：

```text
SQLite knowledge_source
内存 SemanticSearchEngine
indexes/<knowledgeBaseId>.bin
```

添加或删除数据源后，数据库先变化，再异步重建并覆盖索引文件。失败、取消、应用退出或知识库切换都可能让三处状态互相矛盾。旧快照也只记录模型显示名，无法证明模型文件、向量维度和分块版本兼容。

改造后的关键不变量是：

```text
KnowledgeBaseId
SourceRevision
SourceSetHash
EmbeddingModelSignature
EmbeddingDimension
ChunkingVersion
IndexFormatVersion
```

缺失或不匹配任一必要字段时，索引不能被视为完全有效。

## 3. 数据模型与 migration

`DatabaseManager` 维护 `schema_version`，migration 在事务中按顺序执行。当前 schema 为版本 3。

`knowledge_base` 新增：

```text
source_revision INTEGER NOT NULL DEFAULT 0
published_index_revision INTEGER
index_status TEXT NOT NULL DEFAULT 'EMPTY'
last_index_error TEXT NOT NULL DEFAULT ''
last_verified_source_hash TEXT NOT NULL DEFAULT ''
last_verified_at INTEGER
freshness_reason TEXT NOT NULL DEFAULT ''
```

发布记录保存在：

```sql
CREATE TABLE knowledge_index (
    knowledge_base_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    file_name TEXT NOT NULL,
    source_set_hash TEXT NOT NULL,
    embedding_model_signature TEXT NOT NULL,
    embedding_dimension INTEGER NOT NULL,
    chunking_version INTEGER NOT NULL,
    index_format_version INTEGER NOT NULL,
    built_at INTEGER NOT NULL,
    PRIMARY KEY (knowledge_base_id, revision)
);
```

添加或删除 `knowledge_source` 时，以下操作位于同一 SQLite 事务：

1. 修改数据源记录。
2. `source_revision = source_revision + 1`。
3. `index_status = 'DIRTY'`。
4. 清空最近索引错误并记录“数据源配置已变化”的 freshness 原因。

重复添加同一路径或删除不存在的路径不会虚增 revision。

## 4. 索引身份与向量兼容

`IndexSnapshot` 格式版本为 3，并包含 `IndexManifest`：

```java
public record IndexManifest(
        String knowledgeBaseId,
        long sourceRevision,
        String sourceSetHash,
        String embeddingModelSignature,
        int embeddingDimension,
        int chunkingVersion,
        int indexFormatVersion,
        long builtAt
) {}
```

`EmbeddingModelSignature` 包含 provider 类型、模型名、模型文件签名、维度和预处理版本。LangChain4j ONNX adapter 对 ONNX 文件与 tokenizer 文件计算 SHA-256，因此“显示名相同但模型文件不同”不会被误判为兼容。

`SemanticScorer` 是搜索和高亮共享的兼容性与向量评分组件。只有 manifest 签名匹配、索引维度有效、所有文档向量维度一致，查询向量才会参与余弦计算。查询向量维度不一致时会直接降级为词法模式。

旧版 snapshot 没有完整 manifest。恢复时可以读取片段用于保守的词法恢复，但状态会变为 `INCOMPATIBLE`，向量检索和远程 RAG 均被禁止，直到重建。

## 5. 索引状态机

```java
public enum IndexStatus {
    EMPTY,
    READY,
    DIRTY,
    BUILDING,
    FAILED,
    INCOMPATIBLE
}
```

状态转换的主要路径：

```text
EMPTY --添加数据源--> DIRTY
READY --数据源/外部文件变化--> DIRTY
DIRTY --开始构建--> BUILDING
BUILDING --发布成功--> READY
BUILDING --失败--> FAILED
BUILDING --取消/过期--> DIRTY
任意恢复状态 --manifest/模型/格式不匹配--> INCOMPATIBLE
```

`READY` 必须同时满足：

```text
published_index_revision == source_revision
manifest.sourceRevision == source_revision
manifest.sourceSetHash == 当前数据源签名
```

`IndexIdentity.sourceSetHash` 会对规范化数据源路径、目录存在状态，以及其中普通文件的相对路径、大小和修改时间进行哈希，并跳过常见生成目录。应用启动、构建发布前和运行期 reconciliation 都使用同一身份算法。

### 5.1 运行期 freshness

`SourceFreshnessMonitor` 是 application output port，生产 adapter 为 `FileSystemSourceFreshnessMonitor`。它为每个源目录及现有子目录注册 Java NIO `WatchService`，新建目录后继续递归注册，并组合两条检测路径：

```text
低延迟 watcher 事件
周期性 FreshnessReconciler 完整 sourceSetHash 核对
```

普通文件创建、修改或删除事件会立即把 monitor proof 固定为 `CHANGED`，直到下一次构建重新建立基线。即使文件大小相同、mtime 被恢复，也不会因为快速 fingerprint 相同而重新放行。空目录创建等不能直接证明内容变化的事件会 debounce 后核对；`OVERFLOW`、watch key 失效、根目录不可访问或核对异常会进入 `VERIFYING`/`UNAVAILABLE`，远程调用采用保守拒绝。

monitor session 使用递增 epoch。切换知识库或发布新 revision 后，旧 session 的延迟回调会被丢弃，不能覆盖新状态。监听器发现变化时只条件更新当前 `source_revision` 为 `DIRTY` 并使活动 `IndexHandle` stale，不写索引文件。

## 6. 版本化构建与原子发布

开始构建时，应用创建不可变的 `IndexBuildRequest`：

```java
public record IndexBuildRequest(
        String knowledgeBaseId,
        long sourceRevision,
        List<Path> sources,
        String sourceSetHash,
        EmbeddingModelSignature modelSignature
) {}
```

发布顺序：

1. 从 repository 读取并捕获 request。
2. 条件更新数据库状态为 `BUILDING`。
3. 创建独立 `SemanticSearchEngine` builder。
4. 扫描、分块、计算词法特征和 embedding。
5. 生成完整 manifest。
6. 再次计算 source set hash，拒绝构建期间发生的外部文件变化。
7. 写 `<revision>.bin.tmp` 并关闭输出流。
8. 使用 `ATOMIC_MOVE` 发布为 `<revision>.bin`；文件系统不支持原子移动时构建失败，不执行非原子覆盖。
9. 在 SQLite 事务中再次校验 `source_revision` 和状态仍为 `BUILDING`，写 `knowledge_index` 并更新发布指针。
10. 事务提交后，才替换当前内存 `IndexHandle`。

开始构建时 monitor 基线切换到 `IndexBuildRequest.sourceSetHash`。发布成功后则以 manifest 中的 hash 启动新 session，并立即完整核对；因此最终 hash 计算与 SQLite 发布之间发生的文件变化也不会被新 monitor 基线掩盖。

磁盘路径：

```text
%USERPROFILE%\.simplerag\indexes\<knowledgeBaseId>\<revision>.bin
```

构建结果过期时，数据库条件发布返回 false，新 revision 文件会被删除，旧发布索引保持不变。

## 7. 失败、取消与启动恢复

- 扫描或 embedding 失败：记录 `FAILED`，保留旧 `published_index_revision`。
- 保存或原子移动失败：不更新数据库发布记录，也不替换内存索引。
- 用户取消或线程中断：状态回到 `DIRTY`，删除临时文件。
- 构建期间数据库 revision 变化：旧结果作为 stale task 丢弃。
- 应用在 `BUILDING` 时退出：下次启动将其恢复为失败状态，不会永久显示“构建中”。
- 启动时清理 `.tmp` 与数据库未引用的 revision 文件。
- 已发布文件缺失或损坏：状态变为 `INCOMPATIBLE`，要求重建。

这些规则保证故障最多留下可清理的未引用文件，不会让数据库指向半成品。

## 8. RAG freshness 与隐私

`AskUseCase` 在召回和任何 HTTP 请求之前执行 freshness 校验：

```text
status == READY
published_index_revision != null
published_index_revision == source_revision
任务 knowledgeBaseId/sourceRevision == 当前 IndexHandle
SourceFreshnessMonitor proof == VERIFIED
proof knowledgeBaseId/sourceRevision == 当前任务
```

任一条件不满足都抛出本地错误。`AskUseCase` 在召回前检查一次，并在 citations 准备完成、调用 `ChatModel` 前再次检查，缩小异步文件事件期间的发送窗口。`DIRTY`、`BUILDING`、`FAILED`、`INCOMPATIBLE`、`VERIFYING`、`UNAVAILABLE` 和 `STOPPED` 状态不会静默使用旧片段调用远程 API。

允许调用时只发送最多 6 个召回片段的路径、行号、内容和用户问题，不发送整个知识库。API Key 使用 AES-GCM 加密存储；其安全级别是应用级本地保护，不是操作系统凭据保险库。

## 9. 后台任务身份

`IndexHandle` 和 Swing controller 统一绑定：

```text
knowledgeBaseId + sourceRevision
```

`KnowledgeController.TaskIdentity` 在启动搜索、高亮或问答时捕获。后台结果回到 UI 前再次比较当前 identity；知识库切换或 revision 变化后，旧搜索结果、旧高亮、旧引用和旧回答都会被丢弃。

`BackgroundTaskCoordinator` 是 `SwingWorker` 的唯一创建位置，统一负责取消、identity 比较、EDT progress/completion 回调和异常解包。`MainFrame.clearWorkspace` 只通过 `TaskHandle` 取消搜索、高亮和问答；索引构建自身继续检查线程中断。

## 10. 架构与依赖方向

当前仍是单 Maven 模块的模块化单体：

```text
com.simplerag
├─ bootstrap
│  └─ AppCompositionRoot
├─ application
│  ├─ port.in
│  │  ├─ ManageKnowledgeBases
│  │  ├─ ManageKnowledgeSources
│  │  ├─ RebuildKnowledgeIndex
│  │  ├─ SearchKnowledge
│  │  ├─ AskKnowledge
│  │  ├─ ManageApiSettings
│  │  └─ DesktopReadModel
│  ├─ port.out
│  │  ├─ KnowledgeBaseRepository
│  │  ├─ SettingsRepository
│  │  ├─ IndexRepository
│  │  ├─ TextEmbedder
│  │  ├─ ChatModel
│  │  ├─ SecretStore
│  │  └─ SourceFreshnessMonitor
│  ├─ freshness
│  │  ├─ SourceFingerprint / FreshnessSnapshot
│  │  └─ FreshnessGate
│  ├─ runtime
│  │  ├─ ActiveKnowledgeContext
│  │  ├─ ActiveKnowledgeRuntime
│  │  └─ IndexLifecycle
│  ├─ dto
│  │  ├─ DocumentReference / SearchResultView / CitationView
│  │  └─ IndexBuildProgress / IndexBuildResult / AskResultView
│  └─ usecase
│     ├─ KnowledgeBaseUseCases / KnowledgeSourceUseCases
│     ├─ IndexBuildUseCase / SearchUseCase / AskUseCase
│     └─ ApiSettingsUseCase / DesktopQueryService
├─ adapter.in.swing
│  ├─ MainFrame
│  ├─ DesktopWorkspaceController（页面工作流协调）
│  ├─ KnowledgePanel / SearchPanel / AskPanel / StatusBar
│  ├─ BackgroundTaskCoordinator / DesktopFileGateway
│  ├─ KnowledgeController
│  ├─ SearchController
│  ├─ AskController
│  └─ Theme / ThemeBootstrap
├─ adapter.out
│  ├─ sqlite
│  ├─ filesystem (revision index + WatchService/reconciliation)
│  ├─ onnx
│  ├─ openai
│  └─ security
├─ model
├─ search
└─ rag
```

依赖方向：

```text
Swing -> input ports -> use case -> output ports <- adapters
```

只有 `AppCompositionRoot` 在生产代码中创建 SQLite、文件索引、ONNX、HTTP 和密钥 adapter，并显式共享唯一 `ActiveKnowledgeRuntime` 与 freshness monitor。`MainFrame` 不直接持有应用服务或基础设施，而是通过三个页面 controller 调用输入端口。

`SemanticSearchEngine` 是轻量组合对象。扫描、读取、分块、词法特征、查询分析、词法评分、语义评分、混合排名和高亮分别由 `DocumentScanner`、`DocumentReaderRegistry`、`ChunkerRegistry`、`LexicalFeatureExtractor`、`QueryAnalyzer`、`LexicalScorer`、`SemanticScorer`、`RankingPolicy` 与 `SemanticHighlightService` 承担。

## 11. ONNX 与模型下载

本地模型为 `paraphrase-multilingual-MiniLM-L12-v2` 的量化 ONNX 版本，输出 384 维句向量。

`Langchain4jOnnxEmbeddingProvider` 使用：

```text
OnnxEmbeddingModel(onnxPath, tokenizerPath, PoolingMode.MEAN)
```

LangChain4j 的传递依赖提供 Java ONNX Runtime 和 DJL Hugging Face tokenizer。模型懒加载且线程安全。

`ModelDownloader` 位于 `adapter.out.onnx`，使用 JDK `HttpClient` 下载 `tokenizer.json` 与 `model_quint8_avx2.onnx`，临时文件完成后原子移动。入口：

```powershell
.\setup-semantic-model.cmd
```

## 12. 检索与高亮

文档处理：

- 源码按 36 行窗口、8 行重叠分块。
- 普通文档按自然段和长度聚合。
- 单文件最大 2 MB。
- 保留绝对路径、所属 root、行号和修改时间。

词法特征包括 Unicode NFKC 归一化、camelCase/snake_case 拆分、中文二元/三元特征、TF-IDF、文件名加权和少量中英文概念归一。

`RankingPolicy` 当前版本为 1：

```text
混合分数 = 0.78 * 语义分数 + 0.22 * 词法分数
```

语义高亮会把命中片段拆成段落、句子、单行和四行代码窗口，批量计算向量相似度，加入轻微长度惩罚并选取最多两个不重叠区域。同一查询下的定位结果按 `chunkId@limit` 缓存。

## 13. 测试策略

快速测试使用 JUnit 5：

```powershell
mvn.cmd -q test
```

当前一致性测试覆盖：

- 旧 schema migration 保留已有知识库。
- 数据源变更原子递增 revision 并变为 `DIRTY`。
- 成功构建发布到 revision 文件并进入 `READY`。
- 构建期间 revision 变化时拒绝发布。
- 模型签名不匹配时搜索和高亮不调用 query embedding。
- embedding 失败时保留旧发布 revision 并阻止 RAG。
- 外部文件变化在恢复时被识别为 `DIRTY`。
- `READY` 后同会话修改或删除文件会在限定时间内变为 `DIRTY`。
- 删除敏感文件后远程 HTTP 请求次数保持为 0。
- 新建子目录会递归注册，后续文件变化仍可检测。
- `OVERFLOW` 触发完整 reconciliation，监控关闭或根目录不可访问时 gate 保守拒绝。
- 构建期间文件事件不能覆盖较新的 `DIRTY`，旧 monitor 回调也不能污染新 `READY`。
- 切换知识库后旧 identity 的结果被拒绝。
- 取消构建保留旧发布版本且不残留 `.tmp`。
- 启动时把中断的 `BUILDING` 恢复为失败状态并清理 `.tmp`。

另有纯应用层测试使用内存 repository、内存 index repository、fake embedder、fake chat model 和 fake secret store 运行完整重建与问答用例，不启动 Swing、SQLite、ONNX 或 HTTP 服务。
该组测试还注入索引保存失败和数据库发布失败，验证两类故障都不会移动已发布 revision 或替换活动索引。

ArchUnit 检查：

- application 不依赖 Swing 或具体 adapter。
- Swing 不依赖 SQLite、ONNX、OpenAI 或文件系统 adapter。
- 具体基础设施只在 bootstrap/adapter 范围内组装。

完整脚本：

```powershell
.\build-and-test.cmd
```

除 JUnit/ArchUnit 外，脚本还保留两个原入口作为回归基线：

- `SemanticSearchEngineTest`：真实 ONNX 跨语言检索、语义高亮与快照恢复。
- `CourseFeaturesTest`：临时 SQLite、多知识库、AES-GCM、模拟 `/models`、JSON/SSE RAG 与引用。

预期末尾输出：

```text
Vector semantic check: enabled and cross-language match passed
SemanticSearchEngineTest: all checks passed
CourseFeaturesTest: knowledge-base CRUD and RAG API checks passed
```

## 14. 当前限制

- 不解析 PDF、图片、扫描件或 Office 二进制文档。
- 索引使用内存线性扫描，适合个人规模知识库；大量片段可后续接入 HNSW。
- 当前 watcher/reconciliation 只监控本机当前活动知识库；切换到其他知识库时会重新加载索引并执行完整身份校验。
- API Key 尚未接入 Windows Credential Manager。
- `KnowledgeService` 仍保留兼容 facade，供既有命令行回归和构建流程复用；生产桌面组合中的搜索、问答和 API 设置已经直接装配独立用例。

## 15. 第二阶段结构稳定化结果

### 15.1 活动运行态

```text
ActiveKnowledgeContext
  knowledgeBase + sourceRevision + IndexStatus
  FreshnessSnapshot + IndexHandle
            |
            v
ActiveKnowledgeRuntime（唯一原子写入入口）
            |
            v
IndexLifecycle（restore/beginBuild/invalidate/publish/fail/refresh）
```

context 构造时校验 knowledge-base metadata 与 handle identity 完全一致。发布转换还要求 `publishedIndexRevision == sourceRevision`；跨知识库或跨 revision 转换会被拒绝。

### 15.2 Swing 页面与 DTO 边界

`KnowledgePanel`、`SearchPanel` 和 `AskPanel` 自行拥有控件、模型和渲染器，均有不创建 `JFrame` 的独立构造测试。`StatusBar` 单独管理全局状态。`DesktopWorkspaceController` 协调页面工作流，`MainFrame` 只保留窗口组合、模式导航和关闭流程。Swing 只消费 application DTO，不依赖 `DocumentChunk`、`SemanticSearchEngine` 或 `IndexHandle`。

`BackgroundTaskCoordinator` 捕获 `knowledgeBaseId + sourceRevision`，在 EDT 应用结果前重新比较 identity。`DesktopFileGateway` 隔离 `java.awt.Desktop`。

### 15.3 repository 与事务

output port 已拆分为：

```text
KnowledgeBaseRepository
KnowledgeSourceRepository
IndexPublicationRepository
FreshnessRepository
SettingsRepository
```

生产环境仍由同一 `AppRepository` 聚合实现，并通过 `SqliteTransactionManager` 执行跨表事务。添加/删除 source 与 revision/`DIRTY` 更新不能部分提交；索引 manifest 与发布指针仍在同一条件事务内发布。

### 15.4 架构决策与追踪

- ADR 位于 [`docs/adr`](docs/adr)。
- 工程问题、实现类和自动化证据位于 [`docs/REQUIREMENTS_TRACEABILITY.md`](docs/REQUIREMENTS_TRACEABILITY.md)。
- `ArchitectureTest` 固定三层依赖、Swing DTO 边界、用例隔离、检索策略依赖和 `SwingWorker` 所有权。
