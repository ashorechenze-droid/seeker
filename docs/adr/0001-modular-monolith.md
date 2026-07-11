# ADR-0001：单 Maven 模块的模块化单体

- 状态：接受
- 日期：2026-07-11

## 决策

继续使用单 Maven 模块，但以 `adapter.in`、`application`、`search`、`adapter.out` 和 `bootstrap` 建立明确包边界，并由 ArchUnit 持续验证依赖方向。

## 原因与结果

当前桌面应用只有一个部署单元，拆成多个 Maven module 会增加构建与资源装配成本，却不能降低运行态一致性风险。包边界和自动化架构测试已经能够隔离 UI、用例、检索策略与基础设施的变化。若未来出现独立发布或复用需求，再依据真实依赖图拆 module。
