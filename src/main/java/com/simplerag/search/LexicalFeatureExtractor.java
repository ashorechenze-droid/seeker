package com.simplerag.search;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Existing tokenization, concept expansion and TF-IDF feature policy. */
public final class LexicalFeatureExtractor {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+(?:[._/-][a-z0-9]+)*");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "to", "of", "in", "on", "for", "is", "are", "be",
            "with", "this", "that", "how", "what", "from", "by", "as", "it", "at", "into",
            "如何", "怎么", "怎样", "什么", "一下", "一个", "这个", "那个", "可以", "实现", "使用"
    );
    private static final Map<String, List<String>> CONCEPTS = createConcepts();

    public Map<String, Double> weightedTokens(String text, boolean query) {
        Map<String, Double> result = new HashMap<>();
        String normalized = normalize(CAMEL_BOUNDARY.matcher(text).replaceAll(" "));
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (isHan(token)) {
                if (token.length() <= 5 && !STOP_WORDS.contains(token)) result.merge(token, query ? 1.4 : 1.0, Double::sum);
                for (int size = 2; size <= Math.min(3, token.length()); size++) {
                    for (int i = 0; i <= token.length() - size; i++) {
                        String gram = token.substring(i, i + size);
                        if (!STOP_WORDS.contains(gram)) result.merge(gram, query ? 1.25 : 0.9, Double::sum);
                    }
                }
            } else {
                for (String part : token.split("[._/-]+")) {
                    if (part.length() > 1 && !STOP_WORDS.contains(part)) result.merge(part, query ? 1.25 : 1.0, Double::sum);
                }
            }
        }
        String compact = normalized.replaceAll("\\s+", "");
        CONCEPTS.forEach((concept, phrases) -> {
            boolean matched = phrases.stream().anyMatch(phrase -> {
                String normalizedPhrase = normalize(phrase);
                return normalized.contains(normalizedPhrase) || compact.contains(normalizedPhrase.replace(" ", ""));
            });
            if (matched) result.merge("concept:" + concept, query ? 4.0 : 2.8, Double::sum);
        });
        return result;
    }

    public Map<String, Double> tfIdf(Map<String, Double> tokens, Map<String, Integer> df, int documents) {
        Map<String, Double> vector = new HashMap<>();
        tokens.forEach((token, count) -> {
            double tf = 1.0 + Math.log(Math.max(1.0, count));
            double idf = Math.log(1.0 + (documents + 1.0) / (df.getOrDefault(token, 0) + 1.0));
            vector.put(token, tf * idf);
        });
        return vector;
    }

    public double norm(Map<String, Double> vector) {
        return Math.sqrt(vector.values().stream().mapToDouble(value -> value * value).sum());
    }

    public String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('\\', '/');
    }

    private static boolean isHan(String value) {
        return !value.isEmpty() && Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN;
    }

    private static Map<String, List<String>> createConcepts() {
        Map<String, List<String>> concepts = new LinkedHashMap<>();
        concepts.put("database-connection", List.of("数据库连接", "连接数据库", "连数据库", "mysql connection", "database connection", "connect mysql", "jdbc", "datasource", "connection pool", "连接池"));
        concepts.put("authentication", List.of("身份验证", "认证", "登录", "鉴权", "authentication", "authorization", "login", "oauth", "jwt", "token validation"));
        concepts.put("http-api", List.of("接口请求", "调用接口", "网络请求", "http request", "rest api", "fetch api", "axios", "endpoint", "web service"));
        concepts.put("error-handling", List.of("错误处理", "异常处理", "报错", "捕获异常", "error handling", "exception", "try catch", "fallback", "retry", "重试", "接口失败", "请求失败", "超时", "timeout"));
        concepts.put("configuration", List.of("配置文件", "环境变量", "应用配置", "configuration", "environment variable", "env var", "settings", "properties file"));
        concepts.put("file-io", List.of("读写文件", "文件读取", "文件保存", "file io", "read file", "write file", "filesystem", "input stream", "output stream"));
        concepts.put("search", List.of("语义检索", "全文搜索", "查找内容", "semantic search", "full text search", "retrieval", "embedding", "vector search"));
        concepts.put("concurrency", List.of("并发", "异步", "多线程", "协程", "concurrency", "async", "await", "thread pool", "parallel"));
        concepts.put("testing", List.of("单元测试", "自动化测试", "测试用例", "unit test", "integration test", "test case", "assertion", "mock"));
        concepts.put("logging", List.of("日志记录", "日志输出", "排查日志", "logging", "logger", "log output", "observability", "trace"));
        concepts.put("cache", List.of("缓存", "缓存失效", "cache", "redis", "memoization", "ttl"));
        concepts.put("deployment", List.of("部署", "发布上线", "容器化", "deployment", "docker", "container", "kubernetes", "ci cd"));
        concepts.put("data-format", List.of("解析 json", "序列化", "数据格式", "parse json", "serialization", "deserialize", "yaml", "csv"));
        concepts.put("dependency", List.of("安装依赖", "包管理", "依赖冲突", "dependency", "package manager", "npm install", "maven", "pip install"));
        return Map.copyOf(concepts);
    }
}
