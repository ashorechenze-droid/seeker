package com.simplerag.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplerag.adapter.out.onnx.ModelFileSignatureCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Reproducible phase-six benchmark for the persistent model-signature optimization. */
public final class PerformanceBenchmarkMain {
    private PerformanceBenchmarkMain() { }

    public static void main(String[] args) throws Exception {
        Path modelDirectory = Path.of(System.getProperty("simplerag.modelDir", "models/multilingual-minilm"))
                .toAbsolutePath().normalize();
        List<Path> modelFiles = List.of(modelDirectory.resolve("model_quint8_avx2.onnx"),
                modelDirectory.resolve("tokenizer.json"));
        Path dataset = Path.of(args.length > 0 ? args[0] : "examples/knowledge").toAbsolutePath().normalize();
        Path output = Path.of(args.length > 1 ? args[1] : "target/performance/performance-report.json")
                .toAbsolutePath().normalize();

        ObjectMapper json = new ObjectMapper();
        ObjectNode report = json.createObjectNode();
        report.put("schemaVersion", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("datasetPath", dataset.toString());
        report.put("datasetSignature", directorySignature(dataset));
        report.put("datasetFiles", Files.walk(dataset).filter(Files::isRegularFile).count());
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.put("java", System.getProperty("java.version"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());

        ObjectNode benchmark = report.putObject("modelSignatureCache");
        if (modelFiles.stream().allMatch(Files::isRegularFile)) {
            long beforeStarted = System.nanoTime();
            String before = legacySignature(modelFiles);
            long beforeNanos = System.nanoTime() - beforeStarted;
            Path cacheFile = output.getParent().resolve("benchmark-signature-cache.json");
            Files.deleteIfExists(cacheFile);
            ModelFileSignatureCache cache = new ModelFileSignatureCache(cacheFile);
            cache.signature(modelFiles); // populate outside the measured steady-state path
            long afterStarted = System.nanoTime();
            String after = cache.signature(modelFiles);
            long afterNanos = System.nanoTime() - afterStarted;
            benchmark.put("beforeMillis", beforeNanos / 1_000_000.0);
            benchmark.put("afterMillis", afterNanos / 1_000_000.0);
            benchmark.put("speedup", afterNanos == 0 ? 0.0 : (double) beforeNanos / afterNanos);
            benchmark.put("signatureEquivalent", before.equals(after));
        } else {
            benchmark.put("status", "model files unavailable");
        }
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        System.out.println(output);
    }

    private static String legacySignature(List<Path> files) throws Exception {
        MessageDigest combined = MessageDigest.getInstance("SHA-256");
        for (Path file : files) {
            combined.update(ModelFileSignatureCache.hashFile(file)
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        return java.util.HexFormat.of().formatHex(combined.digest());
    }

    private static String directorySignature(Path root) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                digest.update(root.relativize(path).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(ModelFileSignatureCache.hashFile(path)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
