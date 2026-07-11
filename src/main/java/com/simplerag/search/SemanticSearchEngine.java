package com.simplerag.search;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;
import com.simplerag.model.SemanticHighlight;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SemanticSearchEngine {
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+(?:[._/-][a-z0-9]+)*");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", ".simplerag", "node_modules", "target", "build", "dist",
            "out", "vendor", ".venv", "venv", "__pycache__", ".next", ".gradle"
    );
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "rst", "adoc", "java", "kt", "kts", "py", "js", "jsx",
            "ts", "tsx", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "swift",
            "scala", "sql", "sh", "bash", "ps1", "bat", "cmd", "html", "htm", "css", "scss",
            "less", "xml", "json", "jsonl", "yaml", "yml", "toml", "ini", "properties", "conf",
            "vue", "svelte", "gradle", "dockerfile"
    );
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "h",
            "cpp", "hpp", "cs", "php", "rb", "swift", "scala", "sql", "sh", "bash", "ps1",
            "bat", "cmd", "html", "css", "scss", "vue", "svelte"
    );
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "to", "of", "in", "on", "for", "is", "are", "be",
            "with", "this", "that", "how", "what", "from", "by", "as", "it", "at", "into",
            "如何", "怎么", "怎样", "什么", "一下", "一个", "这个", "那个", "可以", "实现", "使用"
    );
    private static final Map<String, List<String>> CONCEPTS = createConcepts();

    private final TextEmbedder embeddingProvider;
    private final SemanticScorer semanticScorer;
    private final RankingPolicy rankingPolicy;
    private volatile State state = State.empty();
    private volatile List<String> roots = List.of();
    private volatile long indexedAt;
    private volatile boolean embeddingsActive;
    private volatile boolean semanticCompatible;
    private volatile IndexManifest manifest;
    private String cachedQueryText = "";
    private float[] cachedQueryEmbedding;
    private String highlightCacheQuery = "";
    private final Map<String, List<SemanticHighlight>> highlightCache = new HashMap<>();

    public SemanticSearchEngine(TextEmbedder embeddingProvider) {
        this(embeddingProvider, new SemanticScorer(), RankingPolicy.defaultPolicy());
    }

    public SemanticSearchEngine(TextEmbedder embeddingProvider, SemanticScorer semanticScorer,
                                RankingPolicy rankingPolicy) {
        this.embeddingProvider = embeddingProvider;
        this.semanticScorer = semanticScorer;
        this.rankingPolicy = rankingPolicy;
    }

    public IndexReport index(List<Path> sourceRoots, Consumer<IndexProgress> progress) throws IOException {
        LinkedHashSet<Path> normalizedRoots = sourceRoots.stream()
                .map(Path::toAbsolutePath).map(Path::normalize)
                .filter(Files::isDirectory)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Path> files = new ArrayList<>();
        for (Path root : normalizedRoots) {
            checkInterrupted();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    return name != null && IGNORED_DIRECTORIES.contains(name.toString().toLowerCase(Locale.ROOT))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && attrs.size() <= MAX_FILE_SIZE && isSupported(file)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        int processed = 0;
        int skipped = 0;
        for (Path file : files) {
            checkInterrupted();
            Path owner = findOwner(file, normalizedRoots);
            try {
                chunks.addAll(chunkFile(file, owner));
            } catch (IOException | RuntimeException unreadable) {
                skipped++;
            }
            processed++;
            if (progress != null && (processed == files.size() || processed % 10 == 0)) {
                progress.accept(new IndexProgress(processed, files.size(), file, "扫描"));
            }
        }

        embeddingsActive = false;
        if (embeddingProvider.isConfigured() && !chunks.isEmpty()) {
            List<DocumentChunk> embeddedChunks = new ArrayList<>(chunks.size());
            final int batchSize = 24;
            for (int start = 0; start < chunks.size(); start += batchSize) {
                checkInterrupted();
                int end = Math.min(chunks.size(), start + batchSize);
                List<DocumentChunk> batch = chunks.subList(start, end);
                List<String> texts = batch.stream()
                        .map(chunk -> chunk.fileName() + "\n" + chunk.content()).toList();
                List<float[]> vectors;
                try {
                    vectors = embeddingProvider.embed(texts);
                } catch (IOException failure) {
                    throw new IOException("生成本地语义向量失败：" + failure.getMessage(), failure);
                }
                for (int i = 0; i < batch.size(); i++) {
                    embeddedChunks.add(batch.get(i).withEmbedding(vectors.get(i)));
                }
                if (progress != null) {
                    progress.accept(new IndexProgress(end, chunks.size(), batch.get(batch.size() - 1).filePath(), "向量化"));
                }
            }
            chunks = embeddedChunks;
            embeddingsActive = true;
            semanticCompatible = true;
        }

        clearHighlightCache();
        this.state = State.build(chunks);
        this.roots = normalizedRoots.stream().map(Path::toString).toList();
        this.indexedAt = System.currentTimeMillis();
        return new IndexReport(files.size() - skipped, chunks.size(), skipped);
    }

    public List<SearchResult> search(String query, int limit, String extensionFilter) {
        String cleaned = query == null ? "" : query.strip();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        State current = state;
        Map<String, Double> queryTokens = weightedTokens(cleaned, true);
        if (queryTokens.isEmpty() && !semanticCompatible) {
            return List.of();
        }
        Map<String, Double> queryVector = tfIdf(queryTokens, current.documentFrequency, current.chunks.size());
        double queryNorm = norm(queryVector);
        float[] semanticQuery = null;
        if (semanticCompatible) {
            try {
                semanticQuery = semanticQuery(cleaned);
                if (!semanticScorer.queryCompatible(semanticQuery,
                        current.chunks.stream().map(item -> item.chunk).toList())) semanticQuery = null;
                embeddingsActive = semanticQuery != null;
            } catch (IOException ignored) {
                embeddingsActive = false;
            }
        }
        String normalizedQuery = normalizeText(cleaned);
        Set<String> queryConcepts = queryTokens.keySet().stream()
                .filter(token -> token.startsWith("concept:"))
                .collect(Collectors.toSet());

        List<SearchResult> results = new ArrayList<>();
        for (IndexedChunk indexed : current.chunks) {
            DocumentChunk chunk = indexed.chunk;
            if (extensionFilter != null && !extensionFilter.isBlank() && !"全部".equals(extensionFilter)
                    && !chunk.extension().equalsIgnoreCase(extensionFilter)) {
                continue;
            }
            double cosine = cosine(queryVector, queryNorm, indexed.vector, indexed.norm);
            String normalizedContent = normalizeText(chunk.content());
            String normalizedName = normalizeText(chunk.fileName());
            double exactBoost = normalizedContent.contains(normalizedQuery) ? 0.24 : 0.0;
            double nameBoost = queryTokens.keySet().stream()
                    .filter(token -> !token.startsWith("concept:") && normalizedName.contains(token))
                    .count() * 0.035;
            long conceptMatches = queryConcepts.stream().filter(indexed.tokens::containsKey).count();
            double conceptBoost = Math.min(0.30, conceptMatches * 0.10);
            double lexicalScore = cosine * 0.74 + exactBoost + nameBoost + conceptBoost;
            double semanticScore = semanticQuery != null && chunk.hasEmbedding()
                    ? Math.max(0.0, semanticScorer.score(semanticQuery, chunk.embedding())) : 0.0;
            double score = rankingPolicy.combine(semanticScore, lexicalScore, semanticQuery != null);
            if (lexicalScore >= rankingPolicy.lexicalResultThreshold()
                    || semanticScore >= rankingPolicy.semanticResultThreshold()) {
                String reason = semanticScore >= rankingPolicy.semanticResultThreshold() && semanticScore >= lexicalScore
                        ? "向量语义匹配" : conceptMatches > 0 ? "语义概念匹配"
                        : exactBoost > 0 ? "原文匹配" : "内容相似";
                results.add(new SearchResult(chunk, Math.min(1.0, score), reason));
            }
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed()
                .thenComparing(result -> result.chunk().fileName()));
        return results.size() <= limit ? results : List.copyOf(results.subList(0, limit));
    }

    public List<SemanticHighlight> semanticHighlights(String query, DocumentChunk chunk, int limit)
            throws IOException {
        String cleaned = query == null ? "" : query.strip();
        if (cleaned.isEmpty() || chunk == null || !chunk.hasEmbedding()
                || !semanticCompatible || limit <= 0) {
            return List.of();
        }
        List<SemanticHighlight> cached = cachedHighlights(cleaned, chunk, limit);
        if (cached != null) return cached;
        List<SemanticHighlight> located = locateHighlights(cleaned, chunk, limit);
        rememberHighlights(cleaned, chunk, limit, located);
        return located;
    }

    private synchronized List<SemanticHighlight> cachedHighlights(String query, DocumentChunk chunk, int limit) {
        return query.equals(highlightCacheQuery) ? highlightCache.get(chunk.id() + "@" + limit) : null;
    }

    private synchronized void rememberHighlights(String query, DocumentChunk chunk, int limit,
                                                 List<SemanticHighlight> highlights) {
        if (!query.equals(highlightCacheQuery)) {
            highlightCache.clear();
            highlightCacheQuery = query;
        }
        highlightCache.put(chunk.id() + "@" + limit, highlights);
    }

    private List<SemanticHighlight> locateHighlights(String cleaned, DocumentChunk chunk, int limit)
            throws IOException {
        float[] queryVector = semanticQuery(cleaned);
        List<TextSpan> candidates = candidateSpans(chunk.content());
        if (candidates.isEmpty()) return List.of();
        List<float[]> vectors = embeddingProvider.embed(candidates.stream().map(TextSpan::text).toList());
        List<ScoredSpan> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            TextSpan span = candidates.get(i);
            double similarity = Math.max(0.0, semanticScorer.score(queryVector, vectors.get(i)));
            double lengthPenalty = Math.min(0.055, Math.log1p(span.text().length() / 90.0) * 0.018);
            scored.add(new ScoredSpan(span, similarity, similarity - lengthPenalty));
        }
        scored.sort(Comparator.comparingDouble(ScoredSpan::rankingScore).reversed());
        List<SemanticHighlight> selected = new ArrayList<>(limit);
        for (ScoredSpan item : scored) {
            if (item.similarity() < 0.18) continue;
            boolean overlaps = selected.stream().anyMatch(existing ->
                    item.span().start() < existing.endOffset() && item.span().end() > existing.startOffset());
            if (!overlaps) {
                selected.add(new SemanticHighlight(item.span().start(), item.span().end(), item.similarity()));
                if (selected.size() == limit) break;
            }
        }
        selected.sort(Comparator.comparingInt(SemanticHighlight::startOffset));
        return List.copyOf(selected);
    }

    public void restore(IndexSnapshot snapshot) {
        clearHighlightCache();
        this.state = State.build(snapshot.chunks());
        this.roots = List.copyOf(snapshot.roots());
        this.indexedAt = snapshot.indexedAt();
        this.manifest = snapshot.manifest();
        this.semanticCompatible = isSemanticCompatible(snapshot);
        this.embeddingsActive = semanticCompatible;
    }

    public IndexSnapshot snapshot() {
        List<DocumentChunk> chunks = state.chunks.stream().map(indexed -> indexed.chunk).toList();
        return new IndexSnapshot(IndexSnapshot.CURRENT_VERSION, roots, chunks, indexedAt,
                state.hasEmbeddings ? embeddingProvider.modelName() : "", manifest);
    }

    public IndexSnapshot snapshot(IndexManifest value) {
        this.manifest = value;
        return snapshot();
    }

    public List<String> roots() {
        return roots;
    }

    public int chunkCount() {
        return state.chunks.size();
    }

    public int fileCount() {
        return (int) state.chunks.stream().map(indexed -> indexed.chunk.path()).distinct().count();
    }

    public Set<String> extensions() {
        return state.chunks.stream().map(indexed -> indexed.chunk.extension())
                .filter(value -> !value.isBlank()).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    public boolean semanticEnabled() {
        return semanticCompatible && embeddingsActive && state.hasEmbeddings;
    }

    public void markStale() {
        semanticCompatible = false;
        embeddingsActive = false;
        clearHighlightCache();
    }

    public boolean semanticModelConfigured() {
        return embeddingProvider.isConfigured();
    }

    public String semanticStatus() {
        if (!embeddingProvider.isConfigured()) return "未安装语义模型";
        if (!state.hasEmbeddings) return "模型已安装，需重建索引";
        if (!semanticCompatible) return "索引向量与当前模型不兼容，需重建";
        return embeddingProvider.status();
    }

    private boolean isSemanticCompatible(IndexSnapshot snapshot) {
        return state.hasEmbeddings && semanticScorer.compatible(embeddingProvider, snapshot.manifest(),
                state.chunks.stream().map(item -> item.chunk).toList());
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("索引构建已取消");
        }
    }

    private synchronized void clearHighlightCache() {
        highlightCache.clear();
        highlightCacheQuery = "";
    }

    private synchronized float[] semanticQuery(String query) throws IOException {
        if (query.equals(cachedQueryText) && cachedQueryEmbedding != null) {
            return cachedQueryEmbedding;
        }
        float[] embedding = embeddingProvider.embed(List.of(query)).get(0);
        cachedQueryEmbedding = embedding;
        cachedQueryText = query;
        return embedding;
    }

    private static List<TextSpan> candidateSpans(String content) {
        List<TextSpan> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher paragraphs = Pattern.compile("(?s)\\S.*?(?=\\R\\s*\\R|\\z)").matcher(content);
        while (paragraphs.find() && result.size() < 80) {
            addSpan(result, seen, content, paragraphs.start(), paragraphs.end());
            BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
            String paragraph = content.substring(paragraphs.start(), paragraphs.end());
            sentences.setText(paragraph);
            int sentenceStart = sentences.first();
            for (int sentenceEnd = sentences.next(); sentenceEnd != BreakIterator.DONE;
                 sentenceStart = sentenceEnd, sentenceEnd = sentences.next()) {
                addSpan(result, seen, content, paragraphs.start() + sentenceStart,
                        paragraphs.start() + sentenceEnd);
            }
        }

        List<TextSpan> lines = new ArrayList<>();
        Matcher lineMatcher = Pattern.compile("(?m)^.*(?:\\R|$)").matcher(content);
        while (lineMatcher.find() && lines.size() < 120) {
            int start = lineMatcher.start();
            int end = lineMatcher.end();
            while (end > start && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) end--;
            if (end > start && !content.substring(start, end).isBlank()) {
                lines.add(new TextSpan(start, end, content.substring(start, end).strip()));
                addSpan(result, seen, content, start, end);
            }
        }
        for (int i = 0; i < lines.size() && result.size() < 120; i += 2) {
            int endIndex = Math.min(lines.size() - 1, i + 3);
            if (endIndex > i) addSpan(result, seen, content, lines.get(i).start(), lines.get(endIndex).end());
        }
        if (result.isEmpty() && !content.isBlank()) addSpan(result, seen, content, 0, content.length());
        return result;
    }

    private static void addSpan(List<TextSpan> result, Set<String> seen, String content, int start, int end) {
        while (start < end && Character.isWhitespace(content.charAt(start))) start++;
        while (end > start && Character.isWhitespace(content.charAt(end - 1))) end--;
        if (end - start < 8 || end - start > 700) return;
        String key = start + ":" + end;
        if (seen.add(key)) result.add(new TextSpan(start, end, content.substring(start, end)));
    }

    private static List<DocumentChunk> chunkFile(Path file, Path root) throws IOException {
        String text = readText(file);
        if (text.isBlank() || looksBinary(text)) {
            return List.of();
        }
        List<String> lines = Arrays.asList(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        String extension = extension(file);
        return CODE_EXTENSIONS.contains(extension)
                ? chunkByWindow(file, root, extension, lines, 36, 8)
                : chunkByParagraph(file, root, extension, lines);
    }

    private static List<DocumentChunk> chunkByWindow(Path file, Path root, String extension,
                                                      List<String> lines, int window, int overlap) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int start = 0; start < lines.size(); start += window - overlap) {
            int end = Math.min(lines.size(), start + window);
            addChunk(chunks, file, root, extension, lines, start, end);
            if (end == lines.size()) {
                break;
            }
        }
        return chunks;
    }

    private static List<DocumentChunk> chunkByParagraph(Path file, Path root, String extension,
                                                         List<String> lines) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int characters = 0;
        for (int i = 0; i < lines.size(); i++) {
            characters += lines.get(i).length() + 1;
            boolean boundary = lines.get(i).isBlank() && characters >= 280;
            boolean full = characters >= 1400;
            if (boundary || full) {
                addChunk(chunks, file, root, extension, lines, start, i + 1);
                start = i + 1;
                characters = 0;
            }
        }
        if (start < lines.size()) {
            addChunk(chunks, file, root, extension, lines, start, lines.size());
        }
        return chunks;
    }

    private static void addChunk(List<DocumentChunk> chunks, Path file, Path root, String extension,
                                 List<String> lines, int start, int end) {
        while (start < end && lines.get(start).isBlank()) start++;
        while (end > start && lines.get(end - 1).isBlank()) end--;
        if (start >= end) return;
        String content = String.join("\n", lines.subList(start, end)).strip();
        if (content.length() < 12) return;
        long modified;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            modified = 0;
        }
        String id = sha1(file.toAbsolutePath() + ":" + (start + 1) + ":" + content);
        chunks.add(new DocumentChunk(id, file.toAbsolutePath().normalize().toString(), root.toString(),
                file.getFileName().toString(), extension, start + 1, end, content, modified, null));
    }

    private static String readText(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException invalidUtf8) {
            return Files.readString(file, Charset.defaultCharset());
        }
    }

    private static boolean looksBinary(String value) {
        int sample = Math.min(value.length(), 2048);
        int controls = 0;
        for (int i = 0; i < sample; i++) {
            char c = value.charAt(i);
            if (c == 0) return true;
            if (c < 9 || (c > 13 && c < 32)) controls++;
        }
        return sample > 0 && controls > sample / 20;
    }

    private static Map<String, Double> weightedTokens(String text, boolean query) {
        Map<String, Double> result = new HashMap<>();
        String camelSplit = CAMEL_BOUNDARY.matcher(text).replaceAll(" ");
        String normalized = normalizeText(camelSplit);
        Matcher matcher = TOKEN_PATTERN.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            if (isHan(token)) {
                if (token.length() <= 5 && !STOP_WORDS.contains(token)) {
                    result.merge(token, query ? 1.4 : 1.0, Double::sum);
                }
                for (int size = 2; size <= Math.min(3, token.length()); size++) {
                    for (int i = 0; i <= token.length() - size; i++) {
                        String gram = token.substring(i, i + size);
                        if (!STOP_WORDS.contains(gram)) result.merge(gram, query ? 1.25 : 0.9, Double::sum);
                    }
                }
            } else {
                for (String part : token.split("[._/-]+")) {
                    if (part.length() > 1 && !STOP_WORDS.contains(part)) {
                        result.merge(part, query ? 1.25 : 1.0, Double::sum);
                    }
                }
            }
        }
        String compact = normalized.replaceAll("\\s+", "");
        CONCEPTS.forEach((concept, phrases) -> {
            boolean matched = phrases.stream().anyMatch(phrase -> {
                String normalizedPhrase = normalizeText(phrase);
                return normalized.contains(normalizedPhrase) || compact.contains(normalizedPhrase.replace(" ", ""));
            });
            if (matched) result.merge("concept:" + concept, query ? 4.0 : 2.8, Double::sum);
        });
        return result;
    }

    private static Map<String, Double> tfIdf(Map<String, Double> tokens, Map<String, Integer> df, int docs) {
        Map<String, Double> vector = new HashMap<>();
        tokens.forEach((token, count) -> {
            double tf = 1.0 + Math.log(Math.max(1.0, count));
            double idf = Math.log(1.0 + (docs + 1.0) / (df.getOrDefault(token, 0) + 1.0));
            vector.put(token, tf * idf);
        });
        return vector;
    }

    private static double cosine(Map<String, Double> left, double leftNorm,
                                 Map<String, Double> right, double rightNorm) {
        if (leftNorm == 0 || rightNorm == 0) return 0;
        Map<String, Double> smaller = left.size() <= right.size() ? left : right;
        Map<String, Double> larger = left.size() <= right.size() ? right : left;
        double dot = 0;
        for (Map.Entry<String, Double> entry : smaller.entrySet()) {
            dot += entry.getValue() * larger.getOrDefault(entry.getKey(), 0.0);
        }
        return dot / (leftNorm * rightNorm);
    }

    private static double norm(Map<String, Double> vector) {
        return Math.sqrt(vector.values().stream().mapToDouble(value -> value * value).sum());
    }

    private static String normalizeText(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('\\', '/');
    }

    private static boolean isHan(String value) {
        return !value.isEmpty() && Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN;
    }

    private static boolean isSupported(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.contains(extension(path)) || fileName.equals("dockerfile")
                || fileName.equals("makefile") || fileName.equals("readme") || fileName.equals("license");
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "text";
    }

    private static Path findOwner(Path file, Set<Path> candidates) {
        return candidates.stream().filter(file::startsWith)
                .max(Comparator.comparingInt(Path::getNameCount)).orElse(file.getParent());
    }

    private static String sha1(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static Map<String, List<String>> createConcepts() {
        Map<String, List<String>> concepts = new LinkedHashMap<>();
        concepts.put("database-connection", List.of("数据库连接", "连接数据库", "连数据库", "mysql connection",
                "database connection", "connect mysql", "jdbc", "datasource", "connection pool", "连接池"));
        concepts.put("authentication", List.of("身份验证", "认证", "登录", "鉴权", "authentication", "authorization",
                "login", "oauth", "jwt", "token validation"));
        concepts.put("http-api", List.of("接口请求", "调用接口", "网络请求", "http request", "rest api", "fetch api",
                "axios", "endpoint", "web service"));
        concepts.put("error-handling", List.of("错误处理", "异常处理", "报错", "捕获异常", "error handling",
                "exception", "try catch", "fallback", "retry", "重试", "接口失败", "请求失败", "超时", "timeout"));
        concepts.put("configuration", List.of("配置文件", "环境变量", "应用配置", "configuration", "environment variable",
                "env var", "settings", "properties file"));
        concepts.put("file-io", List.of("读写文件", "文件读取", "文件保存", "file io", "read file", "write file",
                "filesystem", "input stream", "output stream"));
        concepts.put("search", List.of("语义检索", "全文搜索", "查找内容", "semantic search", "full text search",
                "retrieval", "embedding", "vector search"));
        concepts.put("concurrency", List.of("并发", "异步", "多线程", "协程", "concurrency", "async", "await",
                "thread pool", "parallel"));
        concepts.put("testing", List.of("单元测试", "自动化测试", "测试用例", "unit test", "integration test",
                "test case", "assertion", "mock"));
        concepts.put("logging", List.of("日志记录", "日志输出", "排查日志", "logging", "logger", "log output",
                "observability", "trace"));
        concepts.put("cache", List.of("缓存", "缓存失效", "cache", "redis", "memoization", "ttl"));
        concepts.put("deployment", List.of("部署", "发布上线", "容器化", "deployment", "docker", "container",
                "kubernetes", "ci cd"));
        concepts.put("data-format", List.of("解析 json", "序列化", "数据格式", "parse json", "serialization",
                "deserialize", "yaml", "csv"));
        concepts.put("dependency", List.of("安装依赖", "包管理", "依赖冲突", "dependency", "package manager",
                "npm install", "maven", "pip install"));
        return Map.copyOf(concepts);
    }

    public record IndexProgress(int processed, int total, Path currentFile, String stage) {
    }

    public record IndexReport(int files, int chunks, int skipped) {
    }

    private record IndexedChunk(DocumentChunk chunk, Map<String, Double> tokens,
                                Map<String, Double> vector, double norm) {
    }

    private record TextSpan(int start, int end, String text) {
    }

    private record ScoredSpan(TextSpan span, double similarity, double rankingScore) {
    }

    private record State(List<IndexedChunk> chunks, Map<String, Integer> documentFrequency,
                         boolean hasEmbeddings) {
        static State empty() {
            return new State(List.of(), Map.of(), false);
        }

        static State build(List<DocumentChunk> source) {
            List<Map<String, Double>> tokenMaps = source.stream()
                    .map(chunk -> weightedTokens(chunk.fileName() + "\n" + chunk.content(), false)).toList();
            Map<String, Integer> df = new HashMap<>();
            tokenMaps.forEach(tokens -> new HashSet<>(tokens.keySet()).forEach(token -> df.merge(token, 1, Integer::sum)));
            List<IndexedChunk> indexed = new ArrayList<>(source.size());
            for (int i = 0; i < source.size(); i++) {
                Map<String, Double> tokens = tokenMaps.get(i);
                Map<String, Double> vector = tfIdf(tokens, df, source.size());
                indexed.add(new IndexedChunk(source.get(i), tokens, vector, norm(vector)));
            }
            boolean hasEmbeddings = source.stream().anyMatch(DocumentChunk::hasEmbedding);
            return new State(List.copyOf(indexed), Map.copyOf(df), hasEmbeddings);
        }
    }
}
