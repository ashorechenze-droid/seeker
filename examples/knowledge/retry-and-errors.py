"""HTTP client error handling and retry policy."""

import time


def request_with_retry(client, url, attempts=3):
    for attempt in range(attempts):
        try:
            return client.get(url, timeout=5)
        except TimeoutError:
            if attempt == attempts - 1:
                raise
            time.sleep(2 ** attempt)


# 接口超时采用指数退避，参数错误等 4xx 响应不应重试。
