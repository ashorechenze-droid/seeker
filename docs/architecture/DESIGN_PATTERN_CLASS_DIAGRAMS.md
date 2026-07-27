# SimpleRAG 设计模式类图

本文档依据当前 `src/main/java` 源码和 `docs/architecture/LAYERED_ARCHITECTURE.md` 整理。类图只保留解释模式所需的关键成员，以避免把完整实现细节堆叠到模式图中；关系采用标准 UML 表示法，其中 `<|..` 表示接口实现、`<|--` 表示继承、`*--` 表示组合、`o--` 表示聚合、`-->` 表示关联，`..>` 表示依赖。每种模式对应一个可独立渲染的 PlantUML 文件。

## 1. MVC 模式

PlantUML 源码：[01-mvc.puml](design-patterns/01-mvc.puml)

### 采用原因

项目的语义检索界面同时包含 Swing 控件状态、用户事件编排和实际检索逻辑，如果这些职责都放入一个 Panel，会导致界面代码直接依赖索引运行时并且难以进行单元测试。项目因此让 `SearchPanel` 专注于展示查询、结果和预览，让 `DesktopWorkspaceController` 与 `SearchController` 负责接收回调、调度后台任务及转换调用参数，再通过 `SearchKnowledge` 交给 `SearchUseCase` 执行检索；结果以 `SearchResultView` DTO 返回给界面。这种分离使视图样式、交互流程和检索算法可以独立变化，也避免耗时模型操作侵入 Swing 组件。

源码依据：`adapter/in/swing/SearchPanel.java`、`DesktopWorkspaceController.java`、`SearchController.java`，以及 `application/port/in/SearchKnowledge.java`、`application/usecase/SearchUseCase.java`。

## 2. Adapter 模式

PlantUML 源码：[02-adapter.puml](design-patterns/02-adapter.puml)

### 采用原因

应用层需要的是“列出模型、生成回答和流式生成回答”这些领域能力，而不是 HTTP URL 拼接、鉴权头、JSON 序列化或 SSE 增量解析等传输细节，因此项目定义 `ChatModel` 作为稳定目标接口，并由 `OpenAiCompatibleClient` 把该接口适配到 `java.net.http.HttpClient` 和 OpenAI 兼容 API。这样 `AskUseCase` 只依赖应用端口，远程协议变化被限制在 adapter 内部，同时测试可以注入假的 `ChatModel`，将业务规则验证与网络环境解耦。相同思想也用于 ONNX embedding、Windows Credential Manager、SQLite 和文件系统等外部能力。

源码依据：`application/port/out/ChatModel.java`、`application/usecase/AskUseCase.java` 与 `adapter/out/openai/OpenAiCompatibleClient.java`。

## 3. Repository 模式

PlantUML 源码：[03-repository.puml](design-patterns/03-repository.puml)

### 采用原因

知识库、数据源、索引发布状态、freshness 记录和应用设置都需要持久化，但应用用例不应了解 SQL、表结构、事务和 JDBC 异常处理。项目通过多个细粒度 Repository Port 表达应用真正需要的数据操作，再由 `AppRepository` 统一完成 SQLite 映射，并借助 `DatabaseManager` 与 `SqliteTransactionManager` 管理连接和原子事务。这样既落实了依赖倒置与接口隔离，也使数据存储实现可以替换，并把实体读取、状态发布和事务一致性集中在数据访问层维护。

源码依据：`application/port/out/*Repository.java`、`adapter/out/sqlite/AppRepository.java`、`DatabaseManager.java` 与 `SqliteTransactionManager.java`。

## 4. Strategy 模式

PlantUML 源码：[04-strategy.puml](design-patterns/04-strategy.puml)

### 采用原因

纯文本、PDF、DOCX、PPTX、XLSX 和 HTML 的内容提取方式、限制条件与版本演进完全不同，但它们都需要向后续分块和索引流程提供统一的 `ReadDocument`。项目把读取算法抽象为 `DocumentReader` 策略接口，每种格式提供独立的具体策略，并由上下文在运行时选用。新增格式时只需实现并注册新的 reader，不需要修改扫描、分块、排序或 Swing 调用方，从而满足开闭原则，也便于对每种格式的安全限制和解析结果分别测试。

源码依据：`search/DocumentReader.java`、`DocumentReaderRegistry.java` 与 `search/reader/*DocumentReader.java`。

## 5. Registry 模式

PlantUML 源码：[05-registry.puml](design-patterns/05-registry.puml)

### 采用原因

如果扫描器和索引构建器各自使用文件扩展名条件分支来选择 reader，格式规则会在多个位置重复并逐渐不一致。`DocumentReaderRegistry` 在构造时把 reader 按精确文件名和扩展名建立索引，查找时优先匹配文件名，并在注册阶段拒绝冲突键；`DocumentScanner` 和 `IncrementalIndexBuilder` 只调用 `descriptor` 或 `read`，无需知道具体 reader 类型。集中注册让选择策略、大小限制与版本描述保持一致，同时将新增 reader 的影响限定在注册配置中。

源码依据：`search/DocumentReaderRegistry.java`、`DocumentScanner.java` 与 `IncrementalIndexBuilder.java`。

## 6. Facade 模式

PlantUML 源码：[06-facade.puml](design-patterns/06-facade.puml)

### 采用原因

知识库桌面应用的一次操作往往跨越 Repository、索引文件、embedding、freshness 检查、活动运行时和远程聊天模型，如果 UI 直接协调这些子系统，会形成大量跨层依赖。`KnowledgeService` 作为应用门面实现知识库管理、数据源管理、索引重建、检索、问答、API 设置和桌面只读模型等输入端口，并在内部协调相应子系统；更小的 UseCase 类可以通过这些端口委托给门面。这样调用者获得稳定而面向用例的入口，复杂的恢复、发布、状态校验和资源关闭流程被封装在应用层中。

源码依据：`application/usecase/KnowledgeService.java`、各 `application/port/in` 接口，以及 `KnowledgeBaseUseCases.java`、`KnowledgeSourceUseCases.java`、`IndexBuildUseCase.java` 和 `DesktopQueryService.java`。

## 7. Command 模式

PlantUML 源码：[07-command.puml](design-patterns/07-command.puml)

### 采用原因

检索、语义高亮、索引构建和问答都可能耗时，Swing 又要求界面更新回到 EDT，并且任务还需要统一处理进度、成功、失败、取消和“知识库 revision 已变化”后的结果丢弃。项目用函数式接口 `BackgroundCommand<R, P>` 把每项后台操作封装为命令，`BackgroundTaskCoordinator` 负责通过 `SwingWorker` 执行命令并调用生命周期回调，`TaskHandle` 则提供统一取消能力。具体命令以 lambda 创建，既保留 Command 模式的解耦效果，又避免为每个短任务增加样板类。

源码依据：`adapter/in/swing/BackgroundTaskCoordinator.java` 与 `DesktopWorkspaceController.java`。

## 8. Composition Root / Dependency Injection 模式

PlantUML 源码：[08-composition-root-di.puml](design-patterns/08-composition-root-di.puml)

### 采用原因

系统同时包含 SQLite、文件索引、ONNX、远程 API、凭据存储、运行时、应用用例、控制器和 Swing 窗口，如果各类自行创建依赖，就会把具体技术选择扩散到业务层并使测试难以替换实现。`AppCompositionRoot` 在程序入口集中创建所有 concrete adapter，根据 output port 完成构造器注入，再装配 UseCase、Controller 与 `MainFrame`；除 composition root 外的应用对象只声明所需接口。由此对象生命周期和实现选择集中在单一位置，生产装配清晰可审查，测试也能使用 fake 或 in-memory 实现替换外部依赖。

源码依据：`App.java` 与 `bootstrap/AppCompositionRoot.java`。

## 渲染方式

安装 PlantUML 后，可在项目根目录执行以下 PowerShell 命令批量生成 PNG：

```powershell
plantuml -charset UTF-8 "docs/architecture/design-patterns/*.puml"
```

也可以使用 PlantUML JAR：

```powershell
java -jar plantuml.jar -charset UTF-8 "docs/architecture/design-patterns/*.puml"
```