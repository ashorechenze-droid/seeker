package com.simplerag.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Pure-Java downloader for the local multilingual embedding model. Replaces the retired
 * {@code download_model.py} so model setup no longer requires Python or huggingface_hub.
 *
 * <p>Files are fetched from the configurable Hugging Face mirror (defaults to hf-mirror.com),
 * following redirects to the backing CDN, and written atomically via a temporary file so an
 * interrupted download never leaves a half-written model in place.
 */
public final class ModelDownloader {
    private static final String DEFAULT_MIRROR = "https://hf-mirror.com";
    private static final String REPOSITORY = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2";

    // Remote path on the repo -> local file name inside the target directory.
    private static final String[][] FILES = {
            {"tokenizer.json", "tokenizer.json"},
            {"onnx/model_quint8_avx2.onnx", "model_quint8_avx2.onnx"},
    };

    private final HttpClient httpClient;
    private final String mirror;

    public ModelDownloader() {
        this(resolveMirror());
    }

    public ModelDownloader(String mirror) {
        this.mirror = trimTrailingSlash(mirror);
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public void download(Path targetDirectory) throws IOException, InterruptedException {
        Path target = targetDirectory.toAbsolutePath().normalize();
        Files.createDirectories(target);
        System.out.println("镜像: " + mirror);
        System.out.println("下载多语言语义模型到 " + target);
        for (String[] entry : FILES) {
            downloadFile(entry[0], target.resolve(entry[1]));
        }
        System.out.println("语义模型已就绪。请在 SimpleRAG 中重建索引。");
    }

    private void downloadFile(String remotePath, Path destination) throws IOException, InterruptedException {
        String url = mirror + "/" + REPOSITORY + "/resolve/main/" + remotePath;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "SimpleRAG-ModelDownloader")
                .GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("下载失败 (HTTP " + response.statusCode() + "): " + url);
        }
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".part");
        try (InputStream body = response.body()) {
            long bytes = Files.copy(body, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            System.out.printf("  %s: %.1f MB%n", destination.getFileName(), bytes / 1024.0 / 1024.0);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    private static String resolveMirror() {
        String configured = System.getenv("HF_ENDPOINT");
        return configured == null || configured.isBlank() ? DEFAULT_MIRROR : configured;
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null || value.isBlank() ? DEFAULT_MIRROR : value.strip();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path target = Path.of(args.length > 0 ? args[0] : "models/multilingual-minilm");
        new ModelDownloader().download(target);
    }
}
