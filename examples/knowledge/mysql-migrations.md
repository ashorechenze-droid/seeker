# MySQL schema migrations

数据库结构变更通过递增版本的 migration 管理。应用启动时在事务中依次执行尚未应用的脚本，并把版本写入 `schema_history`。已经发布的 migration 禁止修改，只能新增下一版本。

大表增加索引前应检查锁表时间；生产环境使用 expand-migrate-contract 顺序完成无停机字段变更。这与 JDBC 连接池配置是不同问题。
