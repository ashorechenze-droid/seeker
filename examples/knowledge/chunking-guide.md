# Parent-child chunking guide

Markdown 和 HTML 按标题层级建立 parent section，再在章节内生成 token 限制的 child chunks。检索只为 child 建向量，生成回答时把命中的 child 扩展到相邻片段或父章节上下文。

代码文件优先按照 class、method、function 等声明边界切分，而不是固定字符数。每个 chunk 必须保留文件路径、符号名称和准确行号。MMR 与单文档配额用于减少重复上下文。
