# Product Backlog

优先级使用 P0（必须）、P1（重要）、P2（后续）；故事点用于相对估算，不表示精确工时。

| ID | 用户故事 | 优先级 | 故事点 | 状态 | 验收证据 |
| --- | --- | --- | ---: | --- | --- |
| US-01 | 作为用户，我希望建立并切换本地知识库，以便管理不同资料集合 | P0 | 8 | Done | `KnowledgeBaseUseCases`、`KnowledgePanelTest` |
| US-02 | 作为用户，我希望源文件变化后旧索引自动失效，以免使用过时内容 | P0 | 13 | Done | freshness/consistency 测试、ADR-0005 |
| US-03 | 作为用户，我希望只处理变化文件，以便缩短重建时间 | P1 | 13 | Done | `IncrementalIndexTest`、ADR-0004 |
| US-04 | 作为用户，我希望搜索结果显示准确来源位置，以便打开原文核对 | P0 | 8 | Done | reader/search tests、`SearchPanelTest` |
| US-05 | 作为用户，我希望远程问答发送前看到范围并确认，以便控制数据边界 | P0 | 8 | Done | `AskUseCase`、安全与模拟 API 测试 |
| US-06 | 作为用户，我希望多轮追问仍绑定当前知识库 revision，以免复用旧引用 | P1 | 8 | Done | `ConversationModulesTest` |
| US-07 | 作为维护者，我希望 UI、BLL、DAL、Model 和 Common 职责明确，以便安全扩展和课程验收 | P0 | 5 | Done | `LAYERED_ARCHITECTURE.md`、Common 测试、ArchUnit、完整门禁 |
| US-08 | 作为项目成员，我希望需求、Sprint、Review 与 Retrospective 可追踪，以便验证敏捷开发过程 | P0 | 5 | Done | `docs/agile` 文档集、Sprint 01 Review/Retrospective |
| US-09 | 作为维护者，我希望每次改动都有统一完成标准，以便减少“代码完成但证据缺失” | P1 | 3 | Done | `DEFINITION_OF_DONE.md`、Sprint 01 Review |
| US-10 | 作为用户，我希望扫描件也可检索，以便覆盖图片型 PDF | P2 | 13 | Backlog | 尚未进入 Sprint，需评估 OCR 体积和隐私风险 |
| US-11 | 作为维护者，我希望构建日志没有无行动价值的 provider 警告，以便更快发现真实失败 | P2 | 2 | Ready | 下一 Sprint 评估 Log4j2/SLF4J 依赖策略 |

## 用户故事验收标准

### US-07 五类职责明确

```text
Given 项目生产代码和架构文档
When 检查 package 结构并运行 ArchitectureTest
Then UI、BLL、DAL、Model、Common 均有明确映射
And Common 至少包含两个被不同区域真实使用的公共组件
And Common 不依赖项目高层 package
And 原有功能测试保持通过
```

### US-08 敏捷过程可追踪

```text
Given 当前 Product Backlog 和 Sprint Goal
When Sprint 结束
Then 每个进入 Sprint 的故事都有状态和验收证据
And Review 记录演示与测试结果
And Retrospective 记录至少一个下一迭代改进行动
And 历史整理内容与实时过程记录明确区分
```

### US-09 统一完成标准

```text
Given 任意用户故事准备标记 Done
When 对照 Definition of Done
Then 实现、测试、架构、文档、安全和验收项均已满足
And 未满足项会返回 Backlog 或下一 Sprint
```
