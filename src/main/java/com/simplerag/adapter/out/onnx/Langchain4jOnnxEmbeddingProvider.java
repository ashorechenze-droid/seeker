package com.simplerag.adapter.out.onnx;

import com.simplerag.embedding.EmbeddingProvider;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process sentence-embedding provider backed by LangChain4j's {@link OnnxEmbeddingModel}.
 *
 * <p>This replaces the previous external Python worker. The same quantized multilingual ONNX model
 * ({@code model_quint8_avx2.onnx}) and its {@code tokenizer.json} are loaded directly through the
 * Java ONNX Runtime and the DJL HuggingFace tokenizer, so no Python, NumPy or pip packages are
 * required at runtime. Mean pooling and L2 normalization are performed by the model, matching the
 * behaviour of the retired {@code embedding_worker.py}.
 */
public final class Langchain4jOnnxEmbeddingProvider implements EmbeddingProvider {
    // Kept identical to the retired Python provider so existing index snapshots stay compatible.
    public static final String MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2-int8";
    private static final String MODEL_FILE = "model_quint8_avx2.onnx";
    private static final String TOKENIZER_FILE = "tokenizer.json";

    private final Path modelPath;
    private final Path tokenizerPath;
    private volatile OnnxEmbeddingModel model;
    private volatile String status;
    private volatile String fileSignature;

    public Langchain4jOnnxEmbeddingProvider() {
        this(Path.of(System.getProperty("simplerag.modelDir", "models/multilingual-minilm")));
    }

    public Langchain4jOnnxEmbeddingProvider(Path modelDirectory) {
        Path directory = modelDirectory.toAbsolutePath().normalize();
        this.modelPath = directory.resolve(MODEL_FILE);
        this.tokenizerPath = directory.resolve(TOKENIZER_FILE);
        this.status = isConfigured() ? "模型已就绪" : "未安装语义模型";
    }

    @Override
    public boolean isConfigured() {
        return Files.isRegularFile(modelPath) && Files.isRegularFile(tokenizerPath);
    }

    @Override
    public List<float[]> embed(List<String> texts) throws IOException {
        if (texts.isEmpty()) return List.of();
        OnnxEmbeddingModel embeddingModel = ensureModel();
        List<TextSegment> segments = new ArrayList<>(texts.size());
        for (String text : texts) segments.add(TextSegment.from(text));
        try {
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            List<float[]> vectors = new ArrayList<>(embeddings.size());
            for (Embedding embedding : embeddings) vectors.add(embedding.vector());
            status = "向量语义已启用";
            return vectors;
        } catch (RuntimeException inferenceFailure) {
            status = "模型错误";
            throw new IOException("生成本地语义向量失败：" + inferenceFailure.getMessage(), inferenceFailure);
        }
    }

    private OnnxEmbeddingModel ensureModel() throws IOException {
        OnnxEmbeddingModel current = model;
        if (current != null) return current;
        synchronized (this) {
            if (model == null) {
                if (!isConfigured()) {
                    throw new IOException("语义模型未安装，请先运行 setup-semantic-model.cmd");
                }
                try {
                    model = new OnnxEmbeddingModel(modelPath, tokenizerPath, PoolingMode.MEAN);
                    status = "向量语义已启用";
                } catch (RuntimeException loadFailure) {
                    status = "模型错误";
                    throw new IOException("无法加载本地语义模型：" + loadFailure.getMessage(), loadFailure);
                }
            }
            return model;
        }
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public int dimension() {
        return 384;
    }

    @Override
    public com.simplerag.search.EmbeddingModelSignature signature() {
        return new com.simplerag.search.EmbeddingModelSignature(getClass().getName(), MODEL_NAME,
                modelFilesSignature(), dimension(), 1);
    }

    private String modelFilesSignature() {
        String current = fileSignature;
        if (current != null) return current;
        synchronized (this) {
            if (fileSignature == null) {
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    updateDigest(digest, modelPath);
                    updateDigest(digest, tokenizerPath);
                    fileSignature = java.util.HexFormat.of().formatHex(digest.digest());
                } catch (Exception failure) {
                    fileSignature = "unavailable";
                }
            }
            return fileSignature;
        }
    }

    private static void updateDigest(MessageDigest digest, Path path) throws IOException {
        if (!Files.isRegularFile(path)) return;
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }

    @Override
    public synchronized void close() {
        model = null;
    }
}
