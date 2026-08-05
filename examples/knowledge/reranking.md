# Cross-encoder reranking

双路召回关注 recall，第二阶段重排关注 precision。Cross-encoder 同时读取 query 和 passage，输出相关性分数，因此比独立 embedding 的余弦相似度更适合精排。

生产配置对 RRF 前 20 个候选运行 reranker，最终保留 6 到 8 个片段。若重排模型不可用，系统降级到本地特征重排，并记录 `reranker_fallback` 诊断事件。
