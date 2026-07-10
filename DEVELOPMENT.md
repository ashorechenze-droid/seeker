# SimpleRAG 开发过程记录

## 1. 项目背景

传统关键词搜索要求查询词与原文直接重合，无法稳定处理“数据库连接”与 `MySQL Connection`、中文问题与英文代码注释等表达差异。本项目的目标是在个人电脑上实现一个可实际使用的本地知识与代码片段检索客户端，并保证文件、模型和索引数据不离开本机。

项目采用 Java 17 和 Swing 开发桌面客户端，使用 Maven 管理构建。语义推理由本地 Python ONNX Runtime 进程完成，Java 通过标准输入输出协议调用。检索过程不依赖云端服务；知识问答可以调用用户配置的 OpenAI 兼容 API，也可以指向本地 Ollama 等服务。

## 2. 开发环境检查

开发开始时先检查了项目目录和本机工具链，结果如下：

| 项目 | 检查结果 | 处理方式 |
| --- | --- | --- |
| 原始工程 | 只有 Maven 目录骨架与 `pom.xml` | 在现有结构内继续开发 |
| JDK | `JAVA_HOME` 为 JDK 17 | 将 Maven 编译目标从错误的 Java 19 调整为 17 |
| 系统 Java | PATH 中的 Java 21 启动器会异常退出 | 启动脚本优先使用 `%JAVA_HOME%\bin\javaw.exe` |
| Maven | 3.9.9，可正常构建 | 继续使用 Maven |
| Python | 3.12.7 | 用作本地 ONNX 推理运行时 |
| 推理包 | NumPy、ONNX Runtime、Tokenizers 已安装 | 直接复用本机环境 |
| Ollama | 未安装 | 不依赖 Ollama，采用独立 ONNX 模型 |
| 模型缓存 | 只有未完成的 Hugging Face 元数据 | 从镜像重新下载完整量化模型 |
| SQLite | JDK 不内置 SQLite 驱动 | 引入 `sqlite-jdbc` 并打入自包含 JAR |
| JSON | 不使用字符串拼接解析 API 数据 | 引入 Jackson 处理模型列表和问答请求 |
| Maven 仓库 | 系统 Maven 仓库不可写 | 将依赖定向到项目 `.mvn/repository` |

## 3. 需求分析

功能需求：

1. 用户可以添加或移除多个知识目录，并手动重建索引。
2. 系统递归读取笔记、配置文件和常见编程语言源码。
3. 输入自然语言后，系统能够跨中英文检索语义相关内容。
4. 结果显示文件名、相关度、匹配原因、路径和原始行号。
5. 用户可以预览、复制、打开片段及其所在目录。
6. 语义结果必须标出具体相关句子，不能只高亮相同关键词。
7. 索引需要持久化，后续启动不应重新计算全部文档向量。
8. 用户可以创建、编辑、删除和切换多个知识库。
9. 每个知识库必须拥有独立的数据源和向量索引。
10. 客户端可以配置 OpenAI 兼容 API URL、Key 和模型。
11. 系统可以从 `/models` 获取模型列表。
12. 问答必须基于当前知识库并显示文件与行号引用。

非功能需求：

- 完全本地运行，不上传用户文档。
- 索引和查询在后台线程执行，界面保持响应。
- 跳过生成目录、二进制文件和超过 2 MB 的文件。
- 使用三层架构，界面层不直接依赖索引文件或模型协议。
- 模型下载后能够离线工作。
- API Key 不以明文写入数据库。
- 远程问答只发送召回片段，不发送整个知识库。

## 4. 架构设计

系统分为三层：

```text
表示层 ui
  MainFrame / Theme
        |
        v
应用层 service
  KnowledgeService
        |
        v
领域与数据层 model + search + embedding + repository + rag
  SemanticSearchEngine / IndexStore / EmbeddingProvider
  AppRepository / DatabaseManager / OpenAiCompatibleClient
```

主要职责：

| 模块 | 职责 |
| --- | --- |
| `MainFrame` | 知识库管理、搜索、高亮、API 配置和问答交互 |
| `KnowledgeService` | 编排知识库切换、独立索引、检索、模型列表和问答用例 |
| `SemanticSearchEngine` | 文件扫描、分块、稀疏特征、向量召回、混合排序 |
| `PythonOnnxEmbeddingProvider` | 管理持久 Python 进程并交换批量向量 |
| `embedding_worker.py` | Tokenizer、ONNX 推理、平均池化和向量归一化 |
| `IndexStore` | 使用临时文件和原子替换持久化索引快照 |
| `DocumentChunk` | 保存路径、行号、内容及 384 维文档向量 |
| `AppRepository` | 使用 SQLite 完成知识库、数据源和设置 CRUD |
| `DatabaseManager` | 初始化表结构、外键和连接参数 |
| `SecretCodec` | 使用 AES-GCM 加密本地 API Key |
| `OpenAiCompatibleClient` | 调用 `/models` 和 `/chat/completions` 并解析 JSON |

## 5. 实现阶段

### 5.1 基础索引与桌面客户端

第一阶段实现文件扫描、文本解码和分块。源码采用 36 行重叠窗口，文档按自然段聚合。每个片段保留绝对路径与起止行号。Swing 客户端采用目录、结果、预览三栏布局，耗时操作放入 `SwingWorker`。

### 5.2 初版混合检索

初版实现了 Unicode 归一化、中文二元/三元特征、camelCase 和 snake_case 拆分、TF-IDF 余弦相似度，并加入少量中英文概念映射。该版本可以处理预设的“数据库连接”等表达，但只能覆盖词典中的概念，不属于通用语义模型。

### 5.3 接入真实多语言语义模型

根据实际使用反馈，检索层升级为 `paraphrase-multilingual-MiniLM-L12-v2`。选择该模型的原因是支持中英文、句向量维度较小、适合 CPU 推理，并有约 113 MB 的 INT8 AVX2 ONNX 版本。

模型通过 `https://hf-mirror.com` 下载到项目内的 `models/multilingual-minilm`。Python worker 启动后常驻，Java 使用 Base64 文本请求和 little-endian float32 向量响应，避免每次查询重新加载模型。

索引阶段以 24 个片段为一批生成文档向量。查询阶段只生成一个查询向量，最终评分为：

```text
混合评分 = 0.78 * 语义余弦相似度 + 0.22 * 关键词相关度
```

关键词部分保留原文、文件名和概念加权，用于精确标识符及错误码查询。

### 5.4 语义片段高亮

向量检索最初只能说明整个片段相似，预览中的原词高亮无法标记跨语言结果。为此增加二阶段定位：

1. 将命中的片段拆成句子、段落、单行和四行代码窗口。
2. 批量计算候选段与当前查询的向量相似度。
3. 加入轻微长度惩罚，优先选择更具体的内容。
4. 选择最多两个不重叠片段。
5. 在预览中用绿色背景标注语义片段，黄色继续表示原词命中。
6. 显示最高语义片段相似度，并滚动到第一处命中。

查询向量会被缓存，因此结果排序和片段定位不会重复计算同一个查询。

### 5.5 多知识库与 SQLite

原版本只有一组目录和一个全局索引，无法区分课程资料、项目代码和个人笔记。第二阶段引入 SQLite，并建立三个基础表：

```text
knowledge_base   知识库名称、描述和时间
knowledge_source 知识库与本地目录的关联
app_setting      当前知识库和 API 配置
```

`KnowledgeService` 在切换知识库时创建新的 `SemanticSearchEngine`，并读取 `%USERPROFILE%\.simplerag\indexes\<ID>.bin`。embedding provider 在多个引擎之间共享，避免重复启动 Python 模型进程。删除知识库时只删除数据库记录和索引，不修改用户源文件。

旧版 `%USERPROFILE%\.simplerag\index.bin` 会迁移成“我的知识库”，保留原目录和文档向量。数据库启用外键和级联删除，并设置 busy timeout 处理短时并发访问。

### 5.6 OpenAI 兼容 RAG 问答

客户端增加 API URL、API Key、模型输入框和“获取模型”操作。URL 作为 OpenAI 兼容基础路径，客户端分别调用：

```text
GET  <baseUrl>/models
POST <baseUrl>/chat/completions
```

问答前先在当前知识库执行混合检索，最多选择 6 个片段，给每个片段分配 `[1]` 到 `[6]` 的引用编号。Prompt 包含文件路径、原始行号和片段内容，并要求模型只能依据资料回答。返回后，界面同时显示回答和可双击打开的引用列表。

API Key 使用当前用户名、用户目录和应用固定盐派生 AES 密钥，以随机 IV 的 AES-GCM 密文保存。该方案可以避免 SQLite 明文泄漏，但不等同于 Windows Credential Manager，因此文档中明确说明安全边界。

### 5.7 UI 与打包调整

主窗体增加“语义检索/知识问答”模式切换，左侧管理知识库和数据源，问答页集中放置 API 配置、回答、引用和问题输入。索引、搜索、模型获取和问答均通过 `SwingWorker` 执行。

项目引入 Maven Shade Plugin，把 SQLite JDBC、Jackson、FlatLaf 及其服务注册文件合并到一个约 17 MB 的可执行 JAR。Maven 依赖保存到项目 `.mvn/repository`，避免写入只读的系统仓库。

## 6. 索引与检索流程

```text
添加目录
  -> 扫描支持文件
  -> 文档/代码分块
  -> 提取 TF-IDF 特征
  -> ONNX 批量生成文档向量
  -> 原子保存 index.bin

输入查询
  -> 生成一次查询向量
  -> 稀疏与稠密混合评分
  -> 返回 Top 80
  -> 选中结果
  -> 句子级语义定位
  -> 预览高亮

输入问题
  -> 当前知识库混合检索
  -> 生成带编号上下文
  -> OpenAI 兼容 API
  -> 回答与引用文件列表
```

## 7. 开发中遇到的问题

### Java 启动器不一致

Maven 使用 JDK 17，但系统 PATH 优先指向异常的 Java 21 shim，直接执行 `java` 会以 Windows 错误码退出。启动与测试脚本改为优先使用 `JAVA_HOME`，保证编译和运行使用同一 JDK。

### 初版不是真正的语义检索

概念词典只能处理预设表达。解决方案是明确区分文本回退与向量语义状态，引入多语言 ONNX 模型，并增加未写入词典的跨语言测试。

### 模型下载速度与磁盘位置

官方源下载不稳定，并且默认缓存会写入用户目录。下载脚本改用 `hf-mirror.com`，将临时缓存放到项目 `models/.download-cache`，成功后自动删除，只保留一份模型。

### 语义结果无法高亮原词

跨语言查询本来就没有相同字符，普通字符串高亮必然为空。通过对结果内部候选句再次计算向量相似度，实现了真正的语义位置标注。

### 旧索引兼容

加入文档向量后索引版本升级为 2。`IndexStore` 对版本 1 快照执行迁移；客户端检测到模型已安装但片段没有向量时，会自动重建索引。

### 系统 Maven 仓库不可写

新增 SQLite 和 Jackson 时，Maven 默认尝试写入 `D:\maven\...\mavenrepo` 并收到访问拒绝。通过 `.mvn/maven.config` 将本地仓库固定为项目 `.mvn/repository`，依赖从阿里云镜像下载，构建不再依赖系统目录权限。

### 如何测试外部 API

直接使用真实 API 会引入网络、余额和密钥变量。测试使用 JDK `HttpServer` 在随机本地端口模拟 `/v1/models` 与 `/v1/chat/completions`，同时检查 Authorization header、上下文文件名、模型排序和引用结果。

## 8. 测试与验证

核心测试不依赖 JUnit，使用 Java 断言直接运行，便于在空 Maven 环境中执行。

当前覆盖：

- 扫描 5 个中英文示例文件并生成片段。
- 中文“如何连数据库”命中英文 `MySQL Connection` 笔记。
- 英文登录令牌查询命中中英混合认证笔记。
- 文件类型过滤与接口重试概念共同生效。
- 中文“怎样收起左边栏给编辑区域更多空间”命中纯英文界面笔记。
- 跨语言结果原因必须为“向量语义匹配”。
- 语义定位必须高亮包含 `sidebar` 或 `editor` 的英文句子。
- 索引保存和恢复前后的片段数一致。
- 创建、更新、删除和切换知识库。
- 不同知识库之间的数据源完全隔离。
- SQLite 加密保存 API Key 后可以由同一用户读取。
- 从模拟 `/models` 接口读取并排序模型列表。
- 问答请求携带 Bearer Token 和检索上下文。
- RAG 回答包含引用，引用可以定位到源文件片段。

执行命令：

```powershell
.\build-and-test.cmd
```

通过标志：

```text
Vector semantic check: enabled and cross-language match passed
SemanticSearchEngineTest: all checks passed
CourseFeaturesTest: knowledge-base CRUD and RAG API checks passed
```

## 9. 当前限制与后续方向

- 当前只解析文本和源码，不解析 PDF、图片、扫描件或 Office 二进制文档。
- 单个文件上限为 2 MB，超大日志和生成文件会跳过。
- 索引采用内存线性向量扫描，适合个人知识库；达到几十万片段后应接入 HNSW 近似向量索引。
- 片段定位会在首次选中时执行一次小批量推理；可在后续版本持久化句子级向量以进一步降低延迟。
- 远程 RAG API 会接收召回片段；机密资料应使用本地兼容服务。
- 当前问答使用非流式响应，长回答需要等待完整 JSON 返回。
- API Key 加密是应用级保护，尚未接入 Windows Credential Manager。
- 可以继续加入文件变更监听、索引增量更新、PDF 解析和 OCR。
