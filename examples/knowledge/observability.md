# RAG observability

每次问答生成唯一 `trace_id`，并记录 query rewrite、embedding、BM25、vector search、RRF、rerank、context selection 和 generation 的阶段耗时。

Prometheus 指标包含检索 P50/P95 延迟、缓存命中率、候选数量、token 消耗和降级次数。日志不能记录 API Key、access token 或未经脱敏的完整文档内容。
