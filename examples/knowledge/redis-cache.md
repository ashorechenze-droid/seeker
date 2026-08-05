# Redis cache invalidation

用户资料缓存写入 Redis，键格式为 `profile:{userId}`，TTL 为 15 分钟。数据库更新成功后必须删除对应缓存，不能只等待过期。

```python
def update_profile(user_id, changes):
    repository.update(user_id, changes)
    redis.delete(f"profile:{user_id}")
```

缓存穿透使用短期空值缓存缓解；缓存雪崩通过 TTL 抖动避免大量键同时过期。Redis 只保存可重建数据，不作为用户资料的唯一数据源。
