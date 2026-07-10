# SimpleRAG

SimpleRAG 是一个 Java 桌面端的本地语义知识库客户端，支持多知识库管理、中英文代码与文档检索、语义片段高亮，以及基于 OpenAI 兼容 API 的带引用 RAG 问答。

本地检索使用 `paraphrase-multilingual-MiniLM-L12-v2` 量化 ONNX 模型，通过 LangChain4j 的 in-process `OnnxEmbeddingModel` 在 JVM 内直接推理。文档扫描、分块、向量生成和索引保存均在本机完成，不依赖云端 embedding 服务，也不再需要 Python 运行时。

## 环境要求

- Windows 10/11
- JDK 17 或更高版本
- Maven 3.9 或更高版本

语义推理由 LangChain4j 的 ONNX Runtime（Java 版）和 DJL Hugging Face Tokenizer 完成，全部随 JAR 打包。无需安装 Python、NumPy 或任何 pip 包。

## 快速开始

1. 首次安装语义模型：

```powershell
.\setup-semantic-model.cmd
```

脚本用纯 Java 下载器（无需 Python）通过 `https://hf-mirror.com` 下载约 122 MB 的模型文件到：

```text
D:\SimpleRAG\models\multilingual-minilm
```

2. 启动桌面客户端：

```powershell
.\run-client.cmd
```

也可以手动构建和运行：

```powershell
mvn.cmd -q -DskipTests package
& "$env:JAVA_HOME\bin\java.exe" -jar target\SimpleRAG-1.0-SNAPSHOT.jar
```

项目使用自身的 `.mvn/repository` 保存 Maven 依赖，不修改系统 Maven 仓库。最终生成的 JAR 已包含 SQLite、Jackson、FlatLaf 和其他 Java 依赖。

## 使用流程

### 多知识库

左侧“知识库”区域支持：

- 新建知识库并填写名称、描述。
- 双击或点击编辑修改当前知识库。
- 删除知识库；源文件不会被删除，只清理数据库记录和对应索引。
- 在知识库之间切换，每个知识库拥有独立的数据源和索引。
- 向当前知识库添加或移除多个本地目录。
- 手动重建当前知识库索引。

首次升级时，旧版单知识库索引会迁移为“我的知识库”。系统至少保留一个知识库。

### 语义检索

1. 选择知识库并添加数据源目录。
2. 等待索引完成，底部状态显示“向量语义已启用”。
3. 在“语义检索”页面输入中文、英文、代码标识符或自然语言问题。
4. 可按扩展名过滤结果。
5. 选择结果后查看文件路径、原始行号和内容预览。

黄色背景表示原词命中；绿色背景表示通过二阶段向量定位得到的语义相关句子或代码段。预览栏会显示最高语义片段相似度。

### RAG 知识问答

“知识问答”页面提供以下 API 配置：

- `API URL`：OpenAI 兼容 API 的基础地址。
- `API KEY`：Bearer Token；本地 Ollama 等无需密钥的服务可留空。
- `模型`：可以手动输入，也可以点击“获取模型”从 `/models` 读取。
- “保存”：把 URL、加密后的 Key 和模型名保存到本地 SQLite。

常见 URL 示例：

```text
OpenAI: https://api.openai.com/v1
Ollama: http://localhost:11434/v1
其他兼容服务: https://example.com/v1
```

发送问题时，系统会：

1. 仅检索当前知识库。
2. 选取最多 6 个相关片段，并在右侧立即列出引用文件。
3. 将问题、片段路径、行号和内容发送给配置的 `/chat/completions`。
4. 以流式方式接收回答，答案逐字显示，无需等待完整响应。
5. 要求模型使用 `[1]`、`[2]` 格式标注来源。
6. 在客户端右侧列出引用文件，可双击打开源文件。

生成过程中“发送问题”按钮会变为“停止”，可随时中断当前问答。若目标服务不支持流式（SSE），客户端会自动回退到非流式响应，功能保持可用。

安全边界：本地语义检索不会上传文档；使用远程 RAG API 时，被召回的 Top-K 片段会发送到用户填写的 API URL。敏感知识库应配置本地模型服务。API Key 使用基于当前本机用户的 AES-GCM 加密后写入数据库，但不等同于操作系统凭据保险库。

## 主要功能

- 多知识库新建、编辑、删除、切换和独立数据源管理。
- SQLite 持久化知识库、数据源、当前选择和 API 设置。
- Markdown、纯文本、配置文件及常见编程语言源码递归索引。
- 代码重叠窗口分块、文档自然段分块，并保留路径与行号。
- 384 维中英文句向量和 TF-IDF 混合排序。
- 向量相似度占 78%，关键词、原文、文件名及概念特征占 22%。
- camelCase、snake_case、中文 n-gram 和中英文概念归一。
- 句子级语义定位、原词高亮、文件打开和片段复制。
- OpenAI 兼容模型列表读取和带引用知识问答，支持流式逐字输出与随时停止。
- 同一查询下的语义片段定位结果缓存复用，切换结果无需重复推理。
- 索引与查询后台执行，避免阻塞 Swing 界面。
- 搜索框支持 Esc 一键清空，瞬时状态提示数秒后自动恢复。
- 自动跳过 `.git`、`node_modules`、`target`、`build` 和虚拟环境。
- 旧索引迁移、索引临时文件与原子替换。

单个文件最大读取 2 MB。当前版本不解析 PDF、图片、扫描件或 Office 二进制文档。

## 数据存储

| 数据 | 位置 |
| --- | --- |
| 多语言 ONNX 模型 | `D:\SimpleRAG\models\multilingual-minilm` |
| Maven 项目依赖 | `D:\SimpleRAG\.mvn\repository` |
| SQLite 数据库 | `%USERPROFILE%\.simplerag\simplerag.db` |
| 每知识库索引 | `%USERPROFILE%\.simplerag\indexes\<知识库ID>.bin` |
| 可执行 JAR | `D:\SimpleRAG\target\SimpleRAG-1.0-SNAPSHOT.jar` |

## 系统架构

```text
表示层
  ui/MainFrame, Theme
       |
       v
应用层
  service/KnowledgeService
       |
       +----------------------+----------------------+
       v                      v                      v
检索与模型层                   数据持久层               RAG API 层
search/embedding/model          repository               rag
SemanticSearchEngine            AppRepository             OpenAiCompatibleClient
Langchain4jOnnxEmbeddingProvider DatabaseManager          ApiConfig
IndexStore                      SecretCodec               RagAnswer/RagCitation
```

| 类 | 主要职责 |
| --- | --- |
| `App` | 初始化主题、数据库、应用服务和 Swing 客户端 |
| `MainFrame` | 知识库管理、检索、预览、API 配置和问答交互 |
| `KnowledgeService` | 编排知识库切换、独立索引、检索、模型列表和问答用例 |
| `SemanticSearchEngine` | 扫描、分块、特征提取、向量召回和混合排序 |
| `Langchain4jOnnxEmbeddingProvider` | 用 LangChain4j in-process ONNX 模型在 JVM 内生成句向量 |
| `ModelDownloader` | 纯 Java 从镜像下载本地模型文件，替代 Python 下载脚本 |
| `AppRepository` | 知识库、数据源及应用设置的 SQLite CRUD |
| `IndexStore` | 每知识库索引快照的本地原子持久化 |
| `OpenAiCompatibleClient` | `/models` 与 `/chat/completions` 标准 JSON 通信 |
| `SecretCodec` | API Key 的本机 AES-GCM 加密与解密 |

## 检索与问答流程

```text
数据源目录
  -> 文件扫描与过滤
  -> 文档/代码分块
  -> 稀疏 TF-IDF 特征
  -> 本地 ONNX 批量向量
  -> 当前知识库独立索引

用户查询
  -> 查询向量 + 关键词特征
  -> 混合排序
  -> Top-K 结果
  -> 句子级语义定位与高亮

用户问题
  -> 当前知识库 Top-6 片段
  -> 带编号上下文 Prompt
  -> OpenAI 兼容 API
  -> 回答 + 可打开的引用列表
```

## 构建与测试

```powershell
.\build-and-test.cmd
```

脚本执行两组测试：

- `SemanticSearchEngineTest`：真实 ONNX 中英文检索、过滤、持久化和语义高亮。
- `CourseFeaturesTest`：临时 SQLite 知识库 CRUD、数据源隔离、API Key 保存、模型获取和模拟 RAG 问答。

成功输出：

```text
Vector semantic check: enabled and cross-language match passed
SemanticSearchEngineTest: all checks passed
CourseFeaturesTest: knowledge-base CRUD and RAG API checks passed
```

完整开发过程见 [DEVELOPMENT.md](DEVELOPMENT.md)。
"# seeker" 
