# API authentication

登录成功后服务端签发短期 access token。每次请求在 Authorization header 中携带 Bearer token，过滤器负责校验签名和过期时间。

```java
String header = request.getHeader("Authorization");
String token = header.substring("Bearer ".length());
Claims claims = jwtVerifier.verify(token);
```

Refresh tokens should be stored separately and rotated after use. Never write an access token to application logs.
