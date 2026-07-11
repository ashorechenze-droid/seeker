# ADR-0006：reader、chunker 和 ranking 使用组合策略

- 状态：接受
- 日期：2026-07-11

## 决策

检索由 `DocumentScanner`、`DocumentReaderRegistry`、`ChunkerRegistry`、`LexicalFeatureExtractor`、`QueryAnalyzer`、`LexicalScorer`、`SemanticScorer`、`RankingPolicy` 和 `SemanticHighlightService` 组合。

## 原因与结果

文件格式、分块和排名具有不同变化频率。对象组合允许新增 reader 或替换 ranking policy，而不修改扫描、Swing 或发布协议；golden tests 固定当前算法结果。
