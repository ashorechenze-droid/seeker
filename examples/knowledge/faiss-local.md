# Local FAISS index

离线单机模式使用 FAISS HNSW 保存文本向量。索引文件与 embedding 模型签名、向量维度和知识库 revision 绑定；签名不一致时必须重建，禁止复用旧向量。

HNSW 的 `efSearch` 越大通常召回率越高，但查询延迟也会上升。小于十万片段时应先测量精确扫描，再决定是否启用近似最近邻索引。
