# Incremental index publication

文件 fingerprint 由相对路径、大小、修改时间和 SHA-256 组成。未变化文件复用原有 chunks 与 embeddings，新增或修改文件才重新读取和向量化，删除文件不会进入新 snapshot。

新索引先写入 `.tmp` 文件，完整关闭后原子移动，再在数据库事务中条件更新 published revision。构建期间数据源变化时丢弃过期结果，旧的 READY snapshot 保持可用。
