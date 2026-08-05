# Knowledge-base access control

每个知识库带有 tenant id 和访问级别。检索前先根据当前用户生成允许的 knowledge base 与 document ACL filter，不能先检索全部文档再在结果页隐藏。

管理员权限不应通过客户端布尔值决定。服务端从已经验证的 token claims 中读取角色，并对知识库管理、索引重建和远程发送分别鉴权。
