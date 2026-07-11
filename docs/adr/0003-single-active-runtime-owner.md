# ADR-0003：活动运行态只有一个写入所有者

- 状态：接受
- 日期：2026-07-11

## 决策

`ActiveKnowledgeRuntime` 是活动 `ActiveKnowledgeContext` 的唯一原子写入入口，`IndexLifecycle` 校验 restore、beginBuild、invalidate、publish、fail 和 refresh 转换。

## 原因与结果

知识库 ID、source revision、状态、freshness proof 和 `IndexHandle` 必须作为一个快照变化。集中写入可以拒绝跨知识库或跨 revision 发布，并避免 watcher 回调、构建完成回调和页面任务分别修改不一致的字段。
