# Definition of Done

用户故事只有同时满足下列条件才能标记为 Done：

- 验收标准逐项满足，并有代码、测试、文档或演示证据；
- 新增或修改行为具有相应的单元测试、集成测试或架构测试；
- `mvn test` 通过，且没有失败或错误；
- 高风险或发布前改动执行 `build-and-test.cmd` 完整门禁；
- UI、BLL、DAL、Model、Common 的依赖方向没有被破坏；
- 新的架构决策或重要取舍已更新 ADR/架构文档；
- Product Backlog、Sprint 任务和需求追踪矩阵状态同步；
- 不提交 API Key、凭据、完整敏感正文或包含敏感信息的诊断输出；
- 失败、取消、stale revision 和 adapter 异常仍遵循保守语义；
- Review 可以说明完成内容、未完成内容和验证命令。

如果某项不适用，需要在 Sprint Review 中写明理由，不能静默跳过。
