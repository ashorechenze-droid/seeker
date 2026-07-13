# Sprint 01 Review：Common 层与敏捷证据链

- 日期：2026-07-13
- Sprint Goal：让五类架构职责和敏捷过程都具有代码、自动化测试和文档证据。
- 评审方式：仓库自验；本次未记录外部干系人会议，因此不虚构参与者或反馈。

## 完成内容

### US-07 五类职责明确

- 建立 `com.simplerag.common.crypto.Digests`，统一 SHA-256、文件摘要和 hex 格式化；
- 建立 `com.simplerag.common.text.TextValues`，统一 null-safe trim 和 Locale-independent key；
- 公共能力已被 application、search、rag、ONNX adapter 和 security adapter 使用；
- 新增 `DigestsTest`、`TextValuesTest`；
- 新增 ArchUnit 规则，禁止 Common 依赖项目高层 package；
- 新增 `docs/architecture/LAYERED_ARCHITECTURE.md`，明确 UI/BLL/DAL/Model/Common 映射、OO 原则和设计模式。

### US-08 敏捷过程可追踪

- 建立 Product Vision、Product Backlog、Sprint Backlog、Review 和 Retrospective；
- 将历史完成项与当前实时 Sprint 明确区分；
- 更新需求追踪矩阵和 README 文档入口。

### US-09 统一完成标准

- 建立 `DEFINITION_OF_DONE.md`；
- DoD 包含验收、测试、架构、文档、安全与失败语义检查。

## 验收结果

快速门禁：

```text
mvn test
67 tests, 0 failures, 0 errors, 0 skipped
```

完整门禁：

```text
build-and-test.cmd
Exit code: 0
Vector semantic check: enabled and cross-language match passed
SemanticSearchEngineTest: all checks passed
CourseFeaturesTest: knowledge-base CRUD and RAG API checks passed
Recall@5=1.000
MRR@10=1.000
nDCG@10=0.991
```

生成报告：

- `target/evaluation/retrieval-report.json`
- `target/performance/performance-report.json`

## 未完成内容

Sprint 内选入的 US-07、US-08、US-09 均完成。OCR 等 P2 能力没有进入本 Sprint。

构建仍显示 Log4j2 未找到实现并退回 SimpleLogger 的警告。它不影响当前测试和程序功能，但应在后续 Backlog 中决定是显式加入日志实现，还是通过依赖配置消除噪声。

## 评审结论

Sprint Goal 已达到，US-07、US-08、US-09 满足验收标准和 Definition of Done，可以标记 Done。
