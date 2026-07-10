package com.simplerag.service;

import com.simplerag.embedding.EmbeddingProvider;
import com.simplerag.embedding.Langchain4jOnnxEmbeddingProvider;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.model.SearchResult;
import com.simplerag.model.SemanticHighlight;
import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.OpenAiCompatibleClient;
import com.simplerag.repository.AppRepository;
import com.simplerag.repository.DatabaseManager;
import com.simplerag.repository.SecretCodec;
import com.simplerag.search.IndexSnapshot;
import com.simplerag.search.IndexStore;
import com.simplerag.search.SemanticSearchEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Application-layer facade for knowledge-base, retrieval and RAG use cases. */
public final class KnowledgeService {
    private static final String ACTIVE_KB = "active_knowledge_base";
    private static final String API_URL = "api.base_url";
    private static final String API_KEY = "api.encrypted_key";
    private static final String API_MODEL = "api.model";

    private final EmbeddingProvider embeddingProvider;
    private final AppRepository repository;
    private final DatabaseManager database;
    private final SecretCodec secretCodec;
    private final OpenAiCompatibleClient apiClient;
    private final Path indexesDirectory;
    private SemanticSearchEngine engine;
    private IndexStore store;
    private KnowledgeBase activeKnowledgeBase;

    public KnowledgeService() {
        this(new Langchain4jOnnxEmbeddingProvider(), new DatabaseManager(), new SecretCodec(),
                new OpenAiCompatibleClient(),
                Path.of(System.getProperty("user.home"), ".simplerag", "indexes"));
    }

    public KnowledgeService(EmbeddingProvider embeddingProvider, DatabaseManager database,
                            SecretCodec secretCodec, OpenAiCompatibleClient apiClient, Path indexesDirectory) {
        this.embeddingProvider = embeddingProvider;
        this.database = database;
        this.repository = new AppRepository(database);
        this.secretCodec = secretCodec;
        this.apiClient = apiClient;
        this.indexesDirectory = indexesDirectory.toAbsolutePath().normalize();
        this.engine = new SemanticSearchEngine(embeddingProvider);
    }

    public synchronized boolean restore() {
        List<KnowledgeBase> knowledgeBases = repository.listKnowledgeBases();
        if (knowledgeBases.isEmpty()) {
            KnowledgeBase created = repository.createKnowledgeBase("我的知识库", "从本地目录建立的个人知识库");
            migrateLegacyIndex(created);
            knowledgeBases = List.of(created);
        }
        String preferred = repository.getSetting(ACTIVE_KB).orElse(knowledgeBases.get(0).id());
        KnowledgeBase selected = repository.findKnowledgeBase(preferred).orElse(knowledgeBases.get(0));
        return loadKnowledgeBase(selected);
    }

    public List<KnowledgeBase> knowledgeBases() {
        return repository.listKnowledgeBases();
    }

    public KnowledgeBase currentKnowledgeBase() {
        return activeKnowledgeBase;
    }

    public synchronized KnowledgeBase createKnowledgeBase(String name, String description) {
        KnowledgeBase created = repository.createKnowledgeBase(name, description);
        loadKnowledgeBase(created);
        return created;
    }

    public synchronized KnowledgeBase updateCurrentKnowledgeBase(String name, String description) {
        requireActive();
        activeKnowledgeBase = repository.updateKnowledgeBase(activeKnowledgeBase.id(), name, description);
        return activeKnowledgeBase;
    }

    public synchronized void deleteKnowledgeBase(String id) throws IOException {
        if (repository.listKnowledgeBases().size() <= 1) {
            throw new IllegalStateException("至少需要保留一个知识库");
        }
        repository.deleteKnowledgeBase(id);
        Files.deleteIfExists(indexPath(id));
        if (activeKnowledgeBase != null && activeKnowledgeBase.id().equals(id)) {
            loadKnowledgeBase(repository.listKnowledgeBases().get(0));
        }
    }

    public synchronized boolean selectKnowledgeBase(String id) {
        KnowledgeBase selected = repository.findKnowledgeBase(id)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在"));
        return loadKnowledgeBase(selected);
    }

    public void addSource(Path path) {
        requireActive();
        repository.addSource(activeKnowledgeBase.id(), path);
    }

    public void removeSource(Path path) {
        requireActive();
        repository.removeSource(activeKnowledgeBase.id(), path);
    }

    public SemanticSearchEngine.IndexReport rebuildCurrent(
            Consumer<SemanticSearchEngine.IndexProgress> progress) throws IOException {
        return rebuild(roots(), progress);
    }

    public SemanticSearchEngine.IndexReport rebuild(List<Path> roots,
                                                     Consumer<SemanticSearchEngine.IndexProgress> progress)
            throws IOException {
        requireActive();
        syncSources(roots);
        SemanticSearchEngine.IndexReport report = engine.index(roots(), progress);
        store.save(engine.snapshot());
        return report;
    }

    public List<SearchResult> search(String query, int limit, String extension) {
        return engine.search(query, limit, extension);
    }

    public List<SemanticHighlight> semanticHighlights(String query, DocumentChunk chunk, int limit)
            throws IOException {
        return engine.semanticHighlights(query, chunk, limit);
    }

    public RagAnswer ask(String question, ApiConfig config) throws IOException, InterruptedException {
        return apiClient.answer(config, question, retrieveCitations(question));
    }

    /**
     * Streams a RAG answer. The retrieved citations are handed to {@code onCitations} before generation
     * starts so the UI can render sources immediately, then {@code onDelta} receives each incremental
     * chunk of the generated text.
     */
    public RagAnswer askStream(String question, ApiConfig config, Consumer<List<RagCitation>> onCitations,
                               Consumer<String> onDelta) throws IOException, InterruptedException {
        List<RagCitation> citations = retrieveCitations(question);
        if (onCitations != null) onCitations.accept(citations);
        return apiClient.answerStream(config, question, citations, onDelta);
    }

    private List<RagCitation> retrieveCitations(String question) {
        List<SearchResult> results = search(question, 8, "全部");
        return java.util.stream.IntStream.range(0, Math.min(6, results.size()))
                .mapToObj(index -> new RagCitation(index + 1, results.get(index).chunk(), results.get(index).score()))
                .toList();
    }

    public List<String> fetchModels(ApiConfig config) throws IOException, InterruptedException {
        return apiClient.listModels(config);
    }

    public ApiConfig apiConfig() {
        String url = repository.getSetting(API_URL).orElse("http://localhost:11434/v1");
        String key = secretCodec.decrypt(repository.getSetting(API_KEY).orElse(""));
        String model = repository.getSetting(API_MODEL).orElse("");
        return new ApiConfig(url, key, model);
    }

    public void saveApiConfig(ApiConfig config) {
        config.validateForModels();
        repository.putSetting(API_URL, config.normalizedBaseUrl());
        repository.putSetting(API_KEY, secretCodec.encrypt(config.apiKey()));
        repository.putSetting(API_MODEL, config.model());
    }

    public List<Path> roots() {
        requireActive();
        return repository.listSources(activeKnowledgeBase.id());
    }

    public Set<String> extensions() {
        return engine.extensions();
    }

    public KnowledgeStats stats() {
        IndexSnapshot snapshot = engine.snapshot();
        return new KnowledgeStats(engine.fileCount(), engine.chunkCount(), snapshot.indexedAt(),
                store == null ? indexesDirectory : store.path());
    }

    public boolean semanticEnabled() {
        return engine.semanticEnabled();
    }

    public boolean semanticModelConfigured() {
        return engine.semanticModelConfigured();
    }

    public String semanticStatus() {
        return engine.semanticStatus();
    }

    public Path databasePath() {
        return database.path();
    }

    private boolean loadKnowledgeBase(KnowledgeBase selected) {
        activeKnowledgeBase = selected;
        repository.putSetting(ACTIVE_KB, selected.id());
        engine = new SemanticSearchEngine(embeddingProvider);
        store = new IndexStore(indexPath(selected.id()));
        Optional<IndexSnapshot> snapshot = store.load();
        snapshot.ifPresent(engine::restore);
        return snapshot.isPresent();
    }

    private void migrateLegacyIndex(KnowledgeBase target) {
        IndexStore legacy = new IndexStore();
        legacy.load().ifPresent(snapshot -> {
            snapshot.roots().stream().map(Path::of).forEach(path -> repository.addSource(target.id(), path));
            try {
                new IndexStore(indexPath(target.id())).save(snapshot);
            } catch (IOException ignored) {
                // The sources are retained and can be rebuilt if copying the legacy cache fails.
            }
        });
    }

    private void syncSources(List<Path> requested) {
        LinkedHashSet<Path> normalized = requested.stream().map(Path::toAbsolutePath).map(Path::normalize)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Path> existing = new LinkedHashSet<>(roots());
        existing.stream().filter(path -> !normalized.contains(path)).forEach(this::removeSource);
        normalized.stream().filter(path -> !existing.contains(path)).forEach(this::addSource);
    }

    private Path indexPath(String id) {
        return indexesDirectory.resolve(id + ".bin");
    }

    private void requireActive() {
        if (activeKnowledgeBase == null) throw new IllegalStateException("尚未选择知识库");
    }

    public record KnowledgeStats(int files, int chunks, long indexedAt, Path indexPath) {
    }
}
