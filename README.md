# SimpleRAG

SimpleRAG 是一个 Java 17 + Swing 桌面端本地知识库客户端，支持多知识库、中英文文档与代码检索、语义片段高亮，以及基于 OpenAI 兼容 API 的带引用 RAG 问答。

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

### 2. 重建索引

重建会捕获不可变的：

```text
knowledgeBaseId + sourceRevision + sourceSetHash + embeddingModelSignature
```

扫描、分块和向量化在独立 builder 中完成，不直接修改当前已发布索引。构建完成后先写入：

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

在“语义检索”页输入中文、英文、代码标识符或自然语言问题，可按扩展名过滤结果。结果保留绝对路径和原始行号。

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

问答时最多召回 6 个片段，发送问题、片段路径、行号和内容，并要求模型用 `[1]`、`[2]` 标注引用。回答支持 SSE 流式显示和随时停止；服务不支持 SSE 时自动回退到非流式响应。

远程 RAG 只允许使用 `READY` 且 `published_index_revision == source_revision` 的索引。`DIRTY`、`BUILDING`、`FAILED`、`INCOMPATIBLE` 或缺失索引时，请求会在任何远程调用前被拒绝。

安全边界：

- 本地检索不会上传文档。
- 远程 RAG 会把召回片段发送到用户填写的 API URL，不会发送整个知识库。
- 敏感资料应使用本地兼容模型服务。
- API Key 用当前本机用户派生的 AES-GCM 密钥加密后存入 SQLite，但不等同于 Windows Credential Manager。

## 索引状态

| 状态 | 含义 | 向量检索 | 远程 RAG |
| --- | --- | --- | --- |
| `EMPTY` | 从未建立索引 | 否 | 否 |
| `READY` | 已发布索引与当前 revision 一致 | 模型兼容时允许 | 允许 |
| `DIRTY` | 数据源或外部文件已变化 | 否，词法模式可用 | 否 |
| `BUILDING` | 正在构建指定 revision | 当前界面不发布新结果 | 否 |
| `FAILED` | 最近一次构建失败，旧发布版本仍保留 | 否，词法模式可用 | 否 |
| `INCOMPATIBLE` | 旧索引、模型或格式不兼容 | 否 | 否 |

应用启动时会清理中断构建留下的 `.tmp` 和未被数据库引用的 revision 文件。旧版缺少完整 manifest 的索引不会被静默当作兼容向量索引，而是要求重建。

外部文件内容变化会在下次启动或重建身份校验时被识别；当前版本尚未启用实时文件监听器。

## 支持的内容

- Markdown、纯文本、配置文件和常见编程语言源码。
- 代码重叠窗口分块与文档自然段分块。
- camelCase、snake_case、中文 n-gram、TF-IDF 和少量概念归一。
- 384 维多语言句向量、混合排序和句子级语义定位。
- 自动跳过 `.git`、`node_modules`、`target`、`build`、虚拟环境等目录。

单个文件最大读取 2 MB。当前不解析 PDF、图片、扫描件或 Office 二进制文档。

## 数据存储

| 数据 | 位置 |
| --- | --- |
| 多语言 ONNX 模型 | `D:\SimpleRAG\models\multilingual-minilm` |
| Maven 项目依赖 | `D:\SimpleRAG\.mvn\repository` |
| SQLite 数据库 | `%USERPROFILE%\.simplerag\simplerag.db` |
| 版本化索引 | `%USERPROFILE%\.simplerag\indexes\<知识库ID>\<revision>.bin` |
| 可执行 JAR | `D:\SimpleRAG\target\SimpleRAG-1.0-SNAPSHOT.jar` |

SQLite 使用版本化 migration。`knowledge_base` 保存 `source_revision`、`published_index_revision`、`index_status` 和最近错误；`knowledge_index` 保存每个已发布 revision 的 manifest 元数据。

## 架构

```text
adapter.in.swing
  MainFrame
  KnowledgeController / SearchController / AskController
                |
                v
application.port.in -> application.usecase.KnowledgeService
                |
                v
application.port.out
  KnowledgeBaseRepository / IndexRepository / TextEmbedder
  ChatModel / SecretStore / SettingsRepository
                ^
                |
adapter.out.sqlite / filesystem / onnx / openai / security

bootstrap.AppCompositionRoot
  唯一主要的具体依赖组装位置
```

所有搜索、问答和后台 UI 结果都绑定 `knowledgeBaseId + sourceRevision`。用户切换知识库或 revision 变化后，旧任务结果会被丢弃，不会写入新页面。

检索层把索引身份、向量兼容判断和排名策略拆分为 `IndexManifest`、`IndexIdentity`、`SemanticScorer` 和 `RankingPolicy`。具体 SQLite、文件系统、ONNX 与 HTTP 实现不会被应用层直接创建。

## 构建与测试

运行完整验证：

```powershell
.\build-and-test.cmd
```

脚本依次运行：

- JUnit 5 一致性、迁移、失败、取消、隐私和过期任务测试；
- ArchUnit 依赖方向检查；
- 原有 `SemanticSearchEngineTest` 真实 ONNX 跨语言检索入口；
- 原有 `CourseFeaturesTest` SQLite、模拟 OpenAI JSON/SSE 和引用入口。

也可以只运行快速 JUnit/ArchUnit 测试：

```powershell
mvn.cmd -q test
```

完整开发说明见 [DEVELOPMENT.md](DEVELOPMENT.md)。
