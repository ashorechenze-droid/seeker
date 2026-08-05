# DeepSeek and Qwen providers

DeepSeek 与通义千问通过 OpenAI-compatible chat completions 接口接入。Provider profile 负责默认 base URL、模型名称、上下文窗口、SSE 差异和结构化输出能力。

远程调用设置连接超时、指数退避和最大重试次数。429 应尊重 `Retry-After`，持续失败时触发 circuit breaker；流式响应中断后不能把不完整回答标记为成功。
