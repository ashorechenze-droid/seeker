# ADR-0002：三层架构与 ports/adapters

- 状态：接受
- 日期：2026-07-11

## 决策

表现层位于 `adapter.in.swing`，业务与应用层位于 `application` 和 `search`，数据访问与外部系统位于 `adapter.out`。应用层只依赖输入/输出端口，具体实现仅在 `AppCompositionRoot` 组装。

## 原因与结果

这样可以用内存 fake 测试构建、搜索、问答和失败语义，也能替换 SQLite、ONNX、HTTP 或文件系统实现而不修改页面。ArchUnit 禁止 application 反向依赖 Swing 或具体 adapter。
