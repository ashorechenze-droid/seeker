package com.simplerag.application.usecase;

import com.simplerag.application.port.in.AskKnowledge;
import com.simplerag.application.port.in.DesktopReadModel;
import com.simplerag.application.port.in.ManageApiSettings;
import com.simplerag.application.port.in.ManageKnowledgeBases;
import com.simplerag.application.port.in.ManageKnowledgeSources;
import com.simplerag.application.port.in.RebuildKnowledgeIndex;
import com.simplerag.application.port.in.SearchKnowledge;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.IndexRepository;
import com.simplerag.application.port.out.KnowledgeBaseRepository;
import com.simplerag.application.port.out.SecretStore;
import com.simplerag.application.port.out.SettingsRepository;
import com.simplerag.application.port.out.SourceFreshnessMonitor;
import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.application.freshness.FreshnessGate;
import com.simplerag.application.freshness.FreshnessSnapshot;
import com.simplerag.application.freshness.FreshnessState;
import com.simplerag.application.freshness.SourceFingerprint;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.KnowledgeStats;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.model.SearchResult;
import com.simplerag.model.SemanticHighlight;
import com.simplerag.rag.ApiConfig;
import com.simplerag.search.IndexSnapshot;
import com.simplerag.search.IndexBuildRequest;
import com.simplerag.search.IndexHandle;
import com.simplerag.search.IndexIdentity;
import com.simplerag.search.IndexManifest;
import com.simplerag.search.SemanticSearchEngine;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Application-layer facade for knowledge-base, retrieval and RAG use cases. */
public final class KnowledgeService implements ManageKnowledgeBases, ManageKnowledgeSources,
        RebuildKnowledgeIndex, SearchKnowledge, AskKnowledge, ManageApiSettings, DesktopReadModel, AutoCloseable {
    private static final String ACTIVE_KB = "active_knowledge_base";
    private static final String API_URL = "api.base_url";
    private static final String API_KEY = "api.encrypted_key";
    private static final String API_MODEL = "api.model";

    private final TextEmbedder embeddingProvider;
    private final KnowledgeBaseRepository repository;
    private final SettingsRepository settings;
    private final SecretStore secretCodec;
    private final ChatModel apiClient;
    private final IndexRepository indexRepository;
    private final SourceFreshnessMonitor freshnessMonitor;
    private final FreshnessGate freshnessGate;
    private final java.util.concurrent.atomic.AtomicLong freshnessEpoch = new java.util.concurrent.atomic.AtomicLong();
    private SemanticSearchEngine engine;
    private KnowledgeBase activeKnowledgeBase;
    private volatile IndexHandle activeHandle;

    public KnowledgeService(TextEmbedder embeddingProvider, KnowledgeBaseRepository repository,
                            SettingsRepository settings, SecretStore secretCodec, ChatModel apiClient,
                            IndexRepository indexRepository, SourceFreshnessMonitor freshnessMonitor) {
        this.embeddingProvider = embeddingProvider;
        this.repository = repository;
        this.settings = settings;
        this.secretCodec = secretCodec;
        this.apiClient = apiClient;
        this.indexRepository = indexRepository;
        this.freshnessMonitor = freshnessMonitor;
        this.freshnessGate = new FreshnessGate(freshnessMonitor);
        this.engine = new SemanticSearchEngine(embeddingProvider);
    }

    public synchronized boolean restore() {
        List<KnowledgeBase> knowledgeBases = repository.listKnowledgeBases();
        if (knowledgeBases.isEmpty()) {
            KnowledgeBase created = repository.createKnowledgeBase("我的知识库", "从本地目录建立的个人知识库");
            if (indexRepository.usesDefaultLocation()) migrateLegacyIndex(created);
            knowledgeBases = List.of(created);
        }
        String preferred = settings.getSetting(ACTIVE_KB).orElse(knowledgeBases.get(0).id());
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
        indexRepository.deleteIndex(id);
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
        refreshAfterSourceChange();
        startFreshnessMonitoring();
    }

    public void removeSource(Path path) {
        requireActive();
        repository.removeSource(activeKnowledgeBase.id(), path);
        refreshAfterSourceChange();
        startFreshnessMonitoring();
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
        KnowledgeBase target = repository.findKnowledgeBase(activeKnowledgeBase.id()).orElseThrow();
        List<Path> capturedSources = repository.listSources(target.id());
        IndexBuildRequest request = new IndexBuildRequest(target.id(), target.sourceRevision(), capturedSources,
                IndexIdentity.sourceSetHash(capturedSources), embeddingProvider.signature());
        startFreshnessMonitoring(target, capturedSources, request.sourceSetHash());
        if (!repository.beginIndexBuild(request.knowledgeBaseId(), request.sourceRevision())) {
            throw new StaleTaskException("数据源已变化，请重新开始索引构建");
        }
        refreshActiveKnowledgeBase();
        SemanticSearchEngine builder = new SemanticSearchEngine(embeddingProvider);
        try {
            SemanticSearchEngine.IndexReport report = builder.index(request.sources(), progress);
            IndexSnapshot raw = builder.snapshot();
            int dimension = raw.chunks().stream().filter(DocumentChunk::hasEmbedding)
                    .mapToInt(chunk -> chunk.embedding().length).findFirst().orElse(0);
            String signature = dimension > 0 ? request.modelSignature().value() : "";
            long builtAt = System.currentTimeMillis();
            IndexManifest manifest = new IndexManifest(request.knowledgeBaseId(), request.sourceRevision(),
                    request.sourceSetHash(), signature, dimension, IndexIdentity.CHUNKING_VERSION,
                    IndexSnapshot.CURRENT_VERSION, builtAt);
            IndexSnapshot snapshot = builder.snapshot(manifest);
            String currentSourceHash = IndexIdentity.sourceSetHash(
                    repository.listSources(request.knowledgeBaseId()));
            if (!request.sourceSetHash().equals(currentSourceHash)) {
                throw new StaleTaskException("构建期间源文件已变化，结果已丢弃");
            }
            String fileName = indexRepository.saveRevision(request.knowledgeBaseId(), snapshot);
            if (!repository.publishIndex(manifest, fileName)) {
                indexRepository.deleteRevision(request.knowledgeBaseId(), request.sourceRevision());
                throw new StaleTaskException("构建期间数据源已变化，旧结果已丢弃");
            }
            synchronized (this) {
                if (activeKnowledgeBase != null && activeKnowledgeBase.id().equals(request.knowledgeBaseId())) {
                    engine = builder;
                    activeKnowledgeBase = repository.findKnowledgeBase(request.knowledgeBaseId()).orElseThrow();
                    activeHandle = new IndexHandle(request.knowledgeBaseId(), request.sourceRevision(),
                            IndexStatus.READY, builder);
                }
            }
            startFreshnessMonitoringAfterPublish(manifest);
            return report;
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof StaleTaskException || failure instanceof InterruptedIOException) {
                repository.markIndexDirty(request.knowledgeBaseId(), failure.getMessage());
            } else {
                repository.markIndexBuildFailed(request.knowledgeBaseId(), request.sourceRevision(), failure.getMessage());
            }
            refreshActiveKnowledgeBaseIf(request.knowledgeBaseId());
            throw failure;
        }
    }

    public List<SearchResult> search(String query, int limit, String extension) {
        IndexHandle handle = requireHandle();
        return search(handle.knowledgeBaseId(), handle.sourceRevision(), query, limit, extension);
    }

    public List<SearchResult> search(String knowledgeBaseId, long expectedRevision, String query,
                                     int limit, String extension) {
        IndexHandle handle = requireHandle();
        requireIdentity(handle, knowledgeBaseId, expectedRevision);
        return handle.engine().search(query, limit, extension);
    }

    public List<SemanticHighlight> semanticHighlights(String query, DocumentChunk chunk, int limit)
            throws IOException {
        IndexHandle handle = requireHandle();
        return semanticHighlights(handle.knowledgeBaseId(), handle.sourceRevision(), query, chunk, limit);
    }

    public List<SemanticHighlight> semanticHighlights(String knowledgeBaseId, long expectedRevision,
                                                       String query, DocumentChunk chunk, int limit)
            throws IOException {
        IndexHandle handle = requireHandle();
        requireIdentity(handle, knowledgeBaseId, expectedRevision);
        return handle.engine().semanticHighlights(query, chunk, limit);
    }

    public RagAnswer ask(String question, ApiConfig config) throws IOException, InterruptedException {
        IndexHandle handle = requireReadyHandle();
        List<RagCitation> citations = retrieveCitations(handle, question);
        freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision());
        return apiClient.answer(config, question, citations);
    }

    /**
     * Streams a RAG answer. The retrieved citations are handed to {@code onCitations} before generation
     * starts so the UI can render sources immediately, then {@code onDelta} receives each incremental
     * chunk of the generated text.
     */
    public RagAnswer askStream(String question, ApiConfig config, Consumer<List<RagCitation>> onCitations,
                               Consumer<String> onDelta) throws IOException, InterruptedException {
        IndexHandle handle = requireReadyHandle();
        return askStream(handle.knowledgeBaseId(), handle.sourceRevision(), question, config, onCitations, onDelta);
    }

    public RagAnswer askStream(String knowledgeBaseId, long expectedRevision, String question, ApiConfig config,
                               Consumer<List<RagCitation>> onCitations, Consumer<String> onDelta)
            throws IOException, InterruptedException {
        IndexHandle handle = requireReadyHandle(knowledgeBaseId, expectedRevision);
        List<RagCitation> citations = retrieveCitations(handle, question);
        if (onCitations != null) onCitations.accept(citations);
        freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision());
        return apiClient.answerStream(config, question, citations, onDelta);
    }

    private List<RagCitation> retrieveCitations(IndexHandle handle, String question) {
        List<SearchResult> results = search(handle.knowledgeBaseId(), handle.sourceRevision(), question, 8, "全部");
        return java.util.stream.IntStream.range(0, Math.min(6, results.size()))
                .mapToObj(index -> new RagCitation(index + 1, results.get(index).chunk(), results.get(index).score()))
                .toList();
    }

    public List<String> fetchModels(ApiConfig config) throws IOException, InterruptedException {
        return apiClient.listModels(config);
    }

    public ApiConfig apiConfig() {
        String url = settings.getSetting(API_URL).orElse("http://localhost:11434/v1");
        String key = secretCodec.decrypt(settings.getSetting(API_KEY).orElse(""));
        String model = settings.getSetting(API_MODEL).orElse("");
        return new ApiConfig(url, key, model);
    }

    public void saveApiConfig(ApiConfig config) {
        config.validateForModels();
        settings.putSetting(API_URL, config.normalizedBaseUrl());
        settings.putSetting(API_KEY, secretCodec.encrypt(config.apiKey()));
        settings.putSetting(API_MODEL, config.model());
    }

    public List<Path> roots() {
        requireActive();
        return repository.listSources(activeKnowledgeBase.id());
    }

    public Set<String> extensions() {
        return requireHandle().engine().extensions();
    }

    public KnowledgeStats stats() {
        IndexHandle handle = requireHandle();
        IndexSnapshot snapshot = handle.engine().snapshot();
        return new KnowledgeStats(handle.engine().fileCount(), handle.engine().chunkCount(), snapshot.indexedAt(),
                indexRepository.location(handle.knowledgeBaseId()));
    }

    public boolean semanticEnabled() {
        return requireHandle().status() == IndexStatus.READY && requireHandle().engine().semanticEnabled();
    }

    public boolean semanticModelConfigured() {
        return engine.semanticModelConfigured();
    }

    public String semanticStatus() {
        IndexHandle handle = requireHandle();
        if (handle.status() != IndexStatus.READY) return indexStatusText(handle.status());
        return handle.engine().semanticStatus();
    }

    public String freshnessStatus() {
        FreshnessSnapshot freshness = freshnessMonitor.snapshot();
        KnowledgeBase knowledgeBase = activeKnowledgeBase;
        if (knowledgeBase != null && knowledgeBase.freshnessReason() != null
                && !knowledgeBase.freshnessReason().isBlank()) {
            return knowledgeBase.freshnessReason() + verifiedAtSuffix(knowledgeBase.lastVerifiedAt());
        }
        if (freshness.state() == FreshnessState.VERIFIED && freshness.verifiedAt() > 0) {
            return "源文件已核对 · " + java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(freshness.verifiedAt()));
        }
        return freshness.reason();
    }

    private static String verifiedAtSuffix(Long verifiedAt) {
        if (verifiedAt == null || verifiedAt <= 0) return "";
        String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(verifiedAt));
        return " · 上次核对 " + time;
    }

    public IndexStatus indexStatus() {
        return requireHandle().status();
    }

    public long sourceRevision() {
        return requireHandle().sourceRevision();
    }

    public Path databasePath() {
        return repository.databasePath();
    }

    private boolean loadKnowledgeBase(KnowledgeBase selected) {
        if (selected.indexStatus() == IndexStatus.BUILDING) {
            repository.markIndexBuildFailed(selected.id(), selected.sourceRevision(), "应用退出导致上次构建中断");
            selected = repository.findKnowledgeBase(selected.id()).orElseThrow();
        }
        activeKnowledgeBase = selected;
        settings.putSetting(ACTIVE_KB, selected.id());
        engine = new SemanticSearchEngine(embeddingProvider);
        try {
            indexRepository.cleanTemporaryFiles(selected.id());
            indexRepository.cleanUnreferenced(selected.id(), selected.publishedIndexRevision());
        } catch (IOException ignored) {
        }
        Optional<IndexSnapshot> snapshot = Optional.empty();
        if (selected.publishedIndexRevision() != null) {
            snapshot = indexRepository.loadRevision(selected.id(), selected.publishedIndexRevision());
        }
        if (snapshot.isEmpty()) {
            snapshot = indexRepository.loadLegacy(selected.id());
            if (snapshot.isPresent()) repository.markIndexIncompatible(selected.id(), "旧索引缺少完整 manifest，请重建");
        }
        if (snapshot.isPresent()) {
            engine.restore(snapshot.get());
            validateLoadedIndex(selected, snapshot.get());
        } else if (selected.publishedIndexRevision() != null) {
            repository.markIndexIncompatible(selected.id(), "已发布索引文件不存在或损坏，请重建");
        }
        activeKnowledgeBase = repository.findKnowledgeBase(selected.id()).orElseThrow();
        if (activeKnowledgeBase.indexStatus() != IndexStatus.READY) engine.markStale();
        activeHandle = new IndexHandle(selected.id(), activeKnowledgeBase.sourceRevision(),
                activeKnowledgeBase.indexStatus(), engine);
        startFreshnessMonitoring();
        return snapshot.isPresent();
    }

    private void migrateLegacyIndex(KnowledgeBase target) {
        indexRepository.loadGlobalLegacy().ifPresent(snapshot -> {
            snapshot.roots().stream().map(Path::of).forEach(path -> repository.addSource(target.id(), path));
            repository.markIndexIncompatible(target.id(), "旧索引需要按新格式重建");
        });
    }

    private void syncSources(List<Path> requested) {
        LinkedHashSet<Path> normalized = requested.stream().map(Path::toAbsolutePath).map(Path::normalize)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Path> existing = new LinkedHashSet<>(roots());
        existing.stream().filter(path -> !normalized.contains(path)).forEach(this::removeSource);
        normalized.stream().filter(path -> !existing.contains(path)).forEach(this::addSource);
    }

    private void validateLoadedIndex(KnowledgeBase knowledgeBase, IndexSnapshot snapshot) {
        IndexManifest manifest = snapshot.manifest();
        if (manifest == null || !knowledgeBase.id().equals(manifest.knowledgeBaseId())) {
            repository.markIndexIncompatible(knowledgeBase.id(), "索引缺少有效身份信息，请重建");
            return;
        }
        String currentSourceHash = IndexIdentity.sourceSetHash(repository.listSources(knowledgeBase.id()));
        if (manifest.sourceRevision() != knowledgeBase.sourceRevision()
                || !manifest.sourceSetHash().equals(currentSourceHash)) {
            repository.markIndexDirty(knowledgeBase.id(), "索引对应的数据源版本已过期");
            return;
        }
        if (manifest.indexFormatVersion() != IndexSnapshot.CURRENT_VERSION
                || manifest.chunkingVersion() != IndexIdentity.CHUNKING_VERSION
                || (manifest.embeddingDimension() > 0
                && !manifest.embeddingModelSignature().equals(embeddingProvider.signature().value()))) {
            repository.markIndexIncompatible(knowledgeBase.id(), "索引与当前模型或格式不兼容，请重建");
        }
    }

    private void refreshAfterSourceChange() {
        refreshActiveKnowledgeBase();
        engine.markStale();
        activeHandle = new IndexHandle(activeKnowledgeBase.id(), activeKnowledgeBase.sourceRevision(),
                activeKnowledgeBase.indexStatus(), engine);
    }

    private synchronized void refreshActiveKnowledgeBase() {
        requireActive();
        activeKnowledgeBase = repository.findKnowledgeBase(activeKnowledgeBase.id()).orElseThrow();
        if (activeHandle != null) {
            activeHandle = new IndexHandle(activeKnowledgeBase.id(), activeKnowledgeBase.sourceRevision(),
                    activeKnowledgeBase.indexStatus(), activeHandle.engine());
        }
    }

    private synchronized void refreshActiveKnowledgeBaseIf(String knowledgeBaseId) {
        if (activeKnowledgeBase != null && activeKnowledgeBase.id().equals(knowledgeBaseId)) refreshActiveKnowledgeBase();
    }

    private IndexHandle requireHandle() {
        IndexHandle handle = activeHandle;
        if (handle == null) throw new IllegalStateException("尚未选择知识库");
        return handle;
    }

    private IndexHandle requireReadyHandle() {
        IndexHandle handle = requireHandle();
        return requireReadyHandle(handle.knowledgeBaseId(), handle.sourceRevision());
    }

    private IndexHandle requireReadyHandle(String knowledgeBaseId, long expectedRevision) {
        IndexHandle handle = requireHandle();
        requireIdentity(handle, knowledgeBaseId, expectedRevision);
        KnowledgeBase latest = repository.findKnowledgeBase(handle.knowledgeBaseId()).orElseThrow();
        if (latest.indexStatus() != IndexStatus.READY || latest.publishedIndexRevision() == null
                || latest.sourceRevision() != handle.sourceRevision()
                || latest.publishedIndexRevision() != handle.sourceRevision()) {
            throw new IllegalStateException("当前索引不是最新 READY 状态，已禁止发送远程 RAG 请求");
        }
        freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision());
        return handle;
    }

    private void startFreshnessMonitoring() {
        KnowledgeBase selected = activeKnowledgeBase;
        if (selected == null) return;
        List<Path> sources = repository.listSources(selected.id());
        String currentHash = IndexIdentity.sourceSetHash(sources);
        startFreshnessMonitoring(selected, sources, currentHash);
    }

    private void startFreshnessMonitoring(KnowledgeBase selected, List<Path> sources, String expectedHash) {
        long epoch = freshnessEpoch.incrementAndGet();
        SourceFreshnessMonitor.MonitorRequest request = new SourceFreshnessMonitor.MonitorRequest(
                selected.id(), selected.sourceRevision(), sources, expectedHash);
        freshnessMonitor.start(request, new SourceFreshnessMonitor.Listener() {
            @Override
            public void sourceVerified(SourceFreshnessMonitor.MonitorRequest verifiedRequest,
                                       SourceFingerprint fingerprint) {
                if (freshnessEpoch.get() != epoch) return;
                repository.recordSourceVerification(verifiedRequest.knowledgeBaseId(),
                        verifiedRequest.sourceRevision(), fingerprint.hash(), fingerprint.verifiedAt());
                refreshActiveKnowledgeBaseIf(verifiedRequest.knowledgeBaseId());
            }

            @Override
            public void sourceInvalidated(SourceFreshnessMonitor.MonitorRequest invalidRequest,
                                          SourceFingerprint fingerprint, String reason) {
                if (freshnessEpoch.get() != epoch) return;
                String observedHash = fingerprint == null ? null : fingerprint.hash();
                Long verifiedAt = fingerprint == null ? null : fingerprint.verifiedAt();
                if (repository.markIndexDirtyIfCurrent(invalidRequest.knowledgeBaseId(),
                        invalidRequest.sourceRevision(), reason, observedHash, verifiedAt)) {
                    synchronized (KnowledgeService.this) {
                        if (activeKnowledgeBase != null
                                && activeKnowledgeBase.id().equals(invalidRequest.knowledgeBaseId())) {
                            activeKnowledgeBase = repository.findKnowledgeBase(invalidRequest.knowledgeBaseId())
                                    .orElseThrow();
                            engine.markStale();
                            activeHandle = new IndexHandle(activeKnowledgeBase.id(),
                                    activeKnowledgeBase.sourceRevision(), activeKnowledgeBase.indexStatus(), engine);
                        }
                    }
                }
            }
        });
    }

    private void startFreshnessMonitoringAfterPublish(IndexManifest manifest) {
        KnowledgeBase selected;
        synchronized (this) {
            if (activeKnowledgeBase == null
                    || !activeKnowledgeBase.id().equals(manifest.knowledgeBaseId())
                    || activeKnowledgeBase.sourceRevision() != manifest.sourceRevision()) return;
            selected = activeKnowledgeBase;
        }
        startFreshnessMonitoring(selected, repository.listSources(selected.id()), manifest.sourceSetHash());
    }

    @Override
    public void close() {
        freshnessEpoch.incrementAndGet();
        freshnessMonitor.close();
        embeddingProvider.close();
    }

    private static void requireIdentity(IndexHandle handle, String knowledgeBaseId, long revision) {
        if (!handle.knowledgeBaseId().equals(knowledgeBaseId) || handle.sourceRevision() != revision) {
            throw new StaleTaskException("知识库或数据版本已变化，任务结果已丢弃");
        }
    }

    private static String indexStatusText(IndexStatus status) {
        return switch (status) {
            case EMPTY -> "尚未建立索引";
            case READY -> "索引已就绪";
            case DIRTY -> "数据源已变化，索引待重建";
            case BUILDING -> "正在构建索引";
            case FAILED -> "最近一次索引构建失败";
            case INCOMPATIBLE -> "索引与当前版本不兼容，需重建";
        };
    }

    private void requireActive() {
        if (activeKnowledgeBase == null) throw new IllegalStateException("尚未选择知识库");
    }

}
