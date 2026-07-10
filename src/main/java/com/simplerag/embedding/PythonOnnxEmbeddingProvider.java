package com.simplerag.embedding;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class PythonOnnxEmbeddingProvider implements EmbeddingProvider {
    public static final String MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2-int8";
    private static final String MODEL_FILE = "model_quint8_avx2.onnx";

    private final Path modelDirectory;
    private final Path workerScript;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private volatile String status;
    private int dimension;

    public PythonOnnxEmbeddingProvider() {
        this(Path.of(System.getProperty("simplerag.modelDir", "models/multilingual-minilm")),
                Path.of("scripts", "embedding_worker.py"));
    }

    public PythonOnnxEmbeddingProvider(Path modelDirectory, Path workerScript) {
        this.modelDirectory = modelDirectory.toAbsolutePath().normalize();
        this.workerScript = workerScript.toAbsolutePath().normalize();
        this.status = isConfigured() ? "模型已就绪" : "未安装语义模型";
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "embedding-shutdown"));
    }

    @Override
    public boolean isConfigured() {
        return Files.isRegularFile(modelDirectory.resolve(MODEL_FILE))
                && Files.isRegularFile(modelDirectory.resolve("tokenizer.json"))
                && Files.isRegularFile(workerScript);
    }

    @Override
    public synchronized List<float[]> embed(List<String> texts) throws IOException {
        if (texts.isEmpty()) return List.of();
        ensureStarted();
        StringBuilder request = new StringBuilder("EMBED\t").append(texts.size());
        Base64.Encoder encoder = Base64.getEncoder();
        for (String text : texts) {
            request.append('\t').append(encoder.encodeToString(text.getBytes(StandardCharsets.UTF_8)));
        }
        writer.write(request.toString());
        writer.newLine();
        writer.flush();

        String response = reader.readLine();
        if (response == null) {
            close();
            throw new IOException("本地语义模型进程意外退出");
        }
        String[] fields = response.split("\\t", -1);
        if (fields.length > 1 && "ERROR".equals(fields[0])) {
            String message = new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8);
            status = "模型错误";
            throw new IOException(message);
        }
        if (fields.length != texts.size() + 2 || !"VECTORS".equals(fields[0])) {
            throw new IOException("语义模型返回了无效响应");
        }
        int responseDimension = Integer.parseInt(fields[1]);
        if (dimension != 0 && responseDimension != dimension) {
            throw new IOException("语义向量维度发生变化");
        }
        dimension = responseDimension;
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 2; i < fields.length; i++) {
            byte[] bytes = Base64.getDecoder().decode(fields[i]);
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            float[] vector = new float[responseDimension];
            for (int j = 0; j < vector.length; j++) vector[j] = buffer.getFloat();
            vectors.add(vector);
        }
        status = "向量语义已启用";
        return vectors;
    }

    private void ensureStarted() throws IOException {
        if (!isConfigured()) {
            throw new IOException("语义模型未安装，请先运行 setup-semantic-model.cmd");
        }
        if (process != null && process.isAlive()) return;
        close();
        Path logDirectory = Path.of(System.getProperty("user.home"), ".simplerag");
        Files.createDirectories(logDirectory);
        ProcessBuilder builder = new ProcessBuilder(pythonCommand(), workerScript.toString(),
                "--model-dir", modelDirectory.toString());
        builder.redirectError(ProcessBuilder.Redirect.appendTo(logDirectory.resolve("embedding.log").toFile()));
        process = builder.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String ready = reader.readLine();
        if (ready == null || !ready.startsWith("READY\t")) {
            close();
            throw new IOException("无法启动本地语义模型，请检查 ~/.simplerag/embedding.log");
        }
        String[] fields = ready.split("\\t");
        dimension = Integer.parseInt(fields[1]);
        status = "向量语义已启用";
    }

    private static String pythonCommand() {
        String configured = System.getenv("SIMPLE_RAG_PYTHON");
        return configured == null || configured.isBlank() ? "python" : configured;
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
    public synchronized void close() {
        if (process != null) process.destroy();
        process = null;
        writer = null;
        reader = null;
        dimension = 0;
    }
}
