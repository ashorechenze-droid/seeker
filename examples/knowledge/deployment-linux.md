# Linux container deployment

FastAPI 服务使用非 root 用户运行在 Linux 容器中。健康检查分为 `/health/live` 和 `/health/ready`：存活检查只判断进程，启动就绪检查还验证 MySQL、Redis 和 Elasticsearch 连接。

```bash
docker compose up -d
```

配置通过环境变量注入，镜像中不得包含 API Key。容器收到 SIGTERM 后先停止接收任务，再等待正在执行的索引任务安全结束。
