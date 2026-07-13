# SimpleRAG 五类职责与三层架构映射

## 1. 采用的结构

SimpleRAG 是单一部署单元的模块化单体。项目没有为了目录形式拆成多个 Maven module，而是通过 Java package、输入/输出端口和 ArchUnit 自动化规则落实三层职责。

| 课程要求 | 项目位置 | 主要职责 |
| --- | --- | --- |
| UI 表示层 | `com.simplerag.adapter.in.swing` | Swing 页面、用户输入、页面控制器、后台任务回调 |
| BLL 业务逻辑层 | `com.simplerag.application`、`com.simplerag.search` | 用例编排、运行态、freshness gate、索引与检索规则 |
| DAL 数据访问层 | `com.simplerag.adapter.out` | SQLite、文件系统、ONNX、远程 API、系统凭据实现 |
| Model 实体与传输模型 | `com.simplerag.model`、`com.simplerag.application.dto` | 领域数据、状态、用例输入输出视图 |
| Common 通用类库 | `com.simplerag.common` | 只依赖 JDK 的跨层稳定能力 |
| 组合入口 | `com.simplerag.bootstrap` | 创建具体实现并完成依赖注入 |

## 2. 典型调用方向

```text
Swing Panel
  -> Controller
  -> application.port.in
  -> UseCase / application service
  -> application.port.out
  -> SQLite / filesystem / ONNX / HTTP adapter

UI / BLL / DAL -> Common
Common -X-> UI / BLL / DAL / Model
```

表示层不能直接访问 SQLite、ONNX 或远程 HTTP 实现；应用层依赖 output port 接口；具体 adapter 只在 `AppCompositionRoot` 组装。`ArchitectureTest` 把这些规则变成构建门禁。

## 3. Common 的边界

Common 不是无法归类代码的存放目录。一个组件只有同时满足以下条件才进入 `com.simplerag.common`：

1. 被两个或以上业务区域实际使用；
2. 不包含知识库、索引、问答或界面流程；
3. 不依赖 Swing、SQLite、HTTP、ONNX 和项目高层 package；
4. 可以用小型、确定性的单元测试独立验证。

当前公共组件：

| 组件 | 使用位置 | 用途 |
| --- | --- | --- |
| `common.crypto.Digests` | `search`、`adapter.out.onnx`、`adapter.out.security` | SHA-256 创建、文件摘要、UTF-8 摘要和十六进制格式化 |
| `common.text.TextValues` | `application`、`search`、`rag` | null-safe 清理和 Locale-independent key 规范化 |

`Theme` 仍属于 UI，`ReaderSupport` 仍属于 reader，`SecretCodec` 仍属于安全 adapter；它们不会为了“Common”名称被错误搬迁。

## 4. 自动化证据

- `ArchitectureTest.applicationDoesNotDependOnConcreteAdaptersOrSwing`：BLL 不反向依赖具体 DAL/UI。
- `ArchitectureTest.swingDoesNotReachIntoSqliteIndexStoreOrOnnx`：UI 不绕过用例访问基础设施。
- `ArchitectureTest.infrastructureIsOnlyAssembledByBootstrap`：具体实现集中装配。
- `ArchitectureTest.commonIsAJdkOnlyFoundationAndDoesNotDependOnHigherLayers`：Common 不依赖任何高层项目 package。
- `DigestsTest`、`TextValuesTest`：验证公共组件行为。

完整验收命令为 `build-and-test.cmd`，快速架构与单元测试命令为 `mvn test`。

## 5. 面向对象原则

- 单一职责：Panel、Controller、UseCase、Repository、Reader、后台任务协调器分别承担独立变化原因。
- 依赖倒置：应用层依赖 `ChatModel`、`IndexRepository`、`TextEmbedder` 等接口，具体实现由 bootstrap 注入。
- 接口隔离：知识库、数据源、索引发布、freshness、设置和秘密存储使用不同 output port。
- 开闭原则：新增文档格式通过实现 `DocumentReader` 并注册扩展，不修改检索调用方。

## 6. 已使用的设计模式

| 模式 | 实现证据 |
| --- | --- |
| MVC | Swing Panel + Controller + Model/DTO |
| Adapter | SQLite、filesystem、ONNX、OpenAI、Credential Manager adapter |
| Repository | `AppRepository` 与多个 output repository port |
| Strategy | `DocumentReader`、`TextEmbedder`、`ChatModel` 等可替换策略 |
| Registry | `DocumentReaderRegistry` 按文件名/扩展名选择 reader |
| Facade | `KnowledgeService` 统一知识库与索引相关应用能力 |
| Command | `BackgroundTaskCoordinator.BackgroundCommand` |
| Composition Root / Dependency Injection | `AppCompositionRoot` 集中创建和连接对象 |
