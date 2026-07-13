# Sprint 01：Common 层与敏捷证据链

- 时间：2026-07-13（提前完成）
- Sprint Goal：让五类架构职责和敏捷过程都具有代码、自动化测试和文档证据。
- 选入故事：US-07、US-08、US-09
- 负责人：项目开发者

## Sprint Backlog

| 任务 | 用户故事 | 状态 | 验收方式 |
| --- | --- | --- | --- |
| 识别真正跨层且只依赖 JDK 的公共能力 | US-07 | Done | 重复代码检查与架构边界说明 |
| 新增摘要与文本公共组件并接入不同区域 | US-07 | Done | 生产调用点 + 单元测试 |
| 增加 Common 不反向依赖高层的 ArchUnit 规则 | US-07 | Done | `ArchitectureTest` |
| 编写五类职责映射和设计说明 | US-07 | Done | `LAYERED_ARCHITECTURE.md` |
| 建立 Vision、Backlog 和 Definition of Done | US-08/09 | Done | `docs/agile` |
| 运行快速与完整验证并记录结果 | US-07/08/09 | Done | 67 项测试 + `build-and-test.cmd` |
| 完成 Sprint Review 和 Retrospective | US-08 | Done | `reviews/`、`retrospectives/` |

## 风险与处理

| 风险 | 处理方式 |
| --- | --- |
| Common 变成杂物目录 | 只接受跨区域、JDK-only、可独立测试的组件，并用 ArchUnit 禁止反向依赖 |
| 为课程材料补写不存在的历史 Scrum 仪式 | 历史内容只引用 Git/计划/测试；当前 Sprint 才作为实时过程记录 |
| 公共化改变已有摘要结果 | 使用确定性单元测试并运行全部现有回归 |
| 文档状态与代码不一致 | Review 前重新检查 Git diff、测试报告和 Backlog 状态 |

## Sprint 验收标准

- `com.simplerag.common` 至少有两个实际使用的公共组件；
- Common 同时被 BLL、DAL/adapter 或其他独立区域使用；
- ArchUnit 自动禁止 Common 依赖项目高层 package；
- 五类职责映射、允许依赖和禁止依赖都有文档；
- Vision、Backlog、DoD、Sprint、Review、Retrospective 文件齐全；
- 快速测试和完整门禁通过；
- US-07、US-08、US-09 的最终状态与证据一致。
