# Elasticsearch hybrid retrieval

服务端检索并行执行两条召回链路：Elasticsearch BM25 搜索精确术语，`dense_vector` kNN 搜索语义相近段落。每条链路取前 50 个候选，再用 Reciprocal Rank Fusion 合并。

```text
rrfScore(document) = sum(1 / (60 + rank_i))
```

RRF 不直接比较 BM25 和余弦分数的数值，能避免两种分数量纲不一致。融合后取 20 个候选交给第二阶段 reranker。
