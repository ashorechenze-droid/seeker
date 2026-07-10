# MySQL Connection Notes

The application creates a MySQL connection through JDBC. Keep credentials in environment variables and let the connection pool manage the lifecycle.

```java
String url = System.getenv("DB_URL");
HikariConfig config = new HikariConfig();
config.setJdbcUrl(url);
config.setUsername(System.getenv("DB_USER"));
config.setPassword(System.getenv("DB_PASSWORD"));
config.setMaximumPoolSize(10);
DataSource dataSource = new HikariDataSource(config);
```

生产环境不要在源码中写数据库密码。连接池应设置超时，并在服务停止时关闭 DataSource。
