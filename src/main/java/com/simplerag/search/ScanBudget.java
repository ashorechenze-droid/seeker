package com.simplerag.search;

/**
 * Traversal budget guarding against accidentally huge source roots.
 *
 * <p>An index snapshot is held completely in memory, so an unbounded walk (for example a source root
 * pointing at a drive letter) would exhaust the heap before the build could be cancelled. Reaching a
 * limit stops traversal and is reported as a visible warning rather than silently truncating.
 */
public record ScanBudget(int maxFiles, long maxTotalBytes, int maxDepth) {
    public static final String MAX_FILES_PROPERTY = "simplerag.index.maxFiles";
    public static final String MAX_TOTAL_BYTES_PROPERTY = "simplerag.index.maxTotalBytes";
    public static final String MAX_DEPTH_PROPERTY = "simplerag.index.maxDepth";

    private static final int DEFAULT_MAX_FILES = 50_000;
    private static final long DEFAULT_MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int DEFAULT_MAX_DEPTH = 32;

    public ScanBudget {
        if (maxFiles <= 0) throw new IllegalArgumentException("maxFiles 必须为正数");
        if (maxTotalBytes <= 0) throw new IllegalArgumentException("maxTotalBytes 必须为正数");
        if (maxDepth <= 0) throw new IllegalArgumentException("maxDepth 必须为正数");
    }

    public static ScanBudget fromSystemProperties() {
        return new ScanBudget(
                positiveInt(MAX_FILES_PROPERTY, DEFAULT_MAX_FILES),
                positiveLong(MAX_TOTAL_BYTES_PROPERTY, DEFAULT_MAX_TOTAL_BYTES),
                positiveInt(MAX_DEPTH_PROPERTY, DEFAULT_MAX_DEPTH));
    }

    public Tracker tracker() {
        return new Tracker(this);
    }

    private static int positiveInt(String property, int fallback) {
        long value = positiveLong(property, fallback);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static long positiveLong(String property, long fallback) {
        try {
            long value = Long.parseLong(System.getProperty(property, "").strip());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException absentOrInvalid) {
            return fallback;
        }
    }

    /** Mutable consumption counter; a single instance spans every source root of one scan. */
    public static final class Tracker {
        private final ScanBudget budget;
        private int files;
        private long bytes;
        private String exceeded;

        private Tracker(ScanBudget budget) {
            this.budget = budget;
        }

        /** Accepts one candidate file, or returns false once the budget would be exceeded. */
        public boolean accept(long size) {
            if (exceeded != null) return false;
            long candidateBytes = bytes + Math.max(0, size);
            if (files + 1 > budget.maxFiles()) {
                exceeded = "扫描文件数已达上限 " + budget.maxFiles() + "，后续文件未进入索引（可用 -D"
                        + MAX_FILES_PROPERTY + " 调整）";
                return false;
            }
            if (candidateBytes > budget.maxTotalBytes()) {
                exceeded = "扫描总字节数已达上限 " + budget.maxTotalBytes() + "，后续文件未进入索引（可用 -D"
                        + MAX_TOTAL_BYTES_PROPERTY + " 调整）";
                return false;
            }
            files++;
            bytes = candidateBytes;
            return true;
        }

        public boolean exhausted() {
            return exceeded != null;
        }

        public String reason() {
            return exceeded;
        }
    }
}
