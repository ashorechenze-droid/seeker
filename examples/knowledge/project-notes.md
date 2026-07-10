# 本地检索项目笔记

索引过程分成文件扫描、内容分块、特征提取和倒排统计四步。代码文件保留连续行号，Markdown 笔记按自然段切分。

检索时先把 camelCase 和 snake_case 标识符拆开，再抽取中文二元、三元词组。中英文同义表达会映射到相同概念，例如“语义检索”、semantic search 和 vector retrieval。

所有索引数据保存在用户目录 `.simplerag/index.bin`，不会上传到网络。
