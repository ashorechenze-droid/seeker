# ADR-0004：构建始终发布完整 revision snapshot

- 状态：接受
- 日期：2026-07-11

## 决策

即使未来加入增量索引，builder 也只复用未变化内容来生成新的完整 snapshot，不能原地修改活动索引。

## 原因与结果

完整 snapshot 可以继续使用临时文件、原子移动、SQLite 条件发布和 revision 回滚协议。失败或取消时只需丢弃未发布 revision，不会留下部分更新或幽灵片段。
