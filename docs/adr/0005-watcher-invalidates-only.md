# ADR-0005：watcher 只使索引失效

- 状态：接受
- 日期：2026-07-11

## 决策

`SourceFreshnessMonitor` 发现变化时只能条件标记 `DIRTY`、撤销 freshness proof 并使活动 handle stale；它不能构建或发布索引。

## 原因与结果

文件事件可能重复、丢失或溢出，不能作为发布依据。发布仍必须经过完整扫描、manifest、临时文件、原子移动和数据库条件事务。
