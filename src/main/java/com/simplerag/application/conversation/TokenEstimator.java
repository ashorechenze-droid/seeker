package com.simplerag.application.conversation;

import com.simplerag.application.port.out.SettingsRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-send token budgeting for history trimming.
 *
 * <p>Real consumption is only knowable after a call returns, but trimming has to happen before it,
 * so this starts from a script-aware heuristic and then corrects it per model using the provider's
 * reported {@code prompt_tokens}. The previous {@code chars/4} rule assumed English: for Chinese it
 * undercounted by roughly 3-6x, so a "3000 token" history budget could silently ship four times that.
 *
 * <p>The correction factor is persisted per model, so the budget converges on whatever tokenizer the
 * configured endpoint actually uses — OpenAI, Qwen, DeepSeek or a local model alike.
 */
public final class TokenEstimator {
    /** Roughly one token per ideograph; calibration moves this to the model's real ratio. */
    private static final double CJK_TOKENS_PER_CHAR = 1.0;
    private static final double LATIN_CHARS_PER_TOKEN = 4.0;
    /** Chat templates wrap every message in role/delimiter tokens. */
    public static final int MESSAGE_OVERHEAD_TOKENS = 4;

    private static final double MIN_FACTOR = 0.25;
    private static final double MAX_FACTOR = 4.0;
    private static final double SMOOTHING = 0.3;
    private static final int MIN_OBSERVABLE_PROMPT = 50;
    private static final String SETTING_PREFIX = "tokens.calibration.";

    private final Map<String, Double> factors = new ConcurrentHashMap<>();
    private final SettingsRepository settings;
    private volatile String activeModel = "";

    public TokenEstimator() {
        this(null);
    }

    public TokenEstimator(SettingsRepository settings) {
        this.settings = settings;
    }

    /**
     * Script-aware count before any per-model correction. Kept static and side-effect free so the
     * adapter can measure an outgoing prompt with exactly the same rule used for trimming.
     */
    public static int rawTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int wide = 0;
        int narrow = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isWideScript(codePoint)) wide++;
            else narrow++;
        }
        return (int) Math.ceil(wide * CJK_TOKENS_PER_CHAR + narrow / LATIN_CHARS_PER_TOKEN);
    }

    public static int rawMessageTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += rawTokens(message.content()) + MESSAGE_OVERHEAD_TOKENS;
        }
        return total;
    }

    /** Corrected estimate for the model most recently confirmed by a real usage report. */
    public int estimate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        return (int) Math.ceil(rawMessageTokens(messages) * factor(activeModel));
    }

    public int estimate(String text) {
        return (int) Math.ceil(rawTokens(text) * factor(activeModel));
    }

    /**
     * Folds one real {@code prompt_tokens} report into the model's correction factor. Observations
     * are smoothed and clamped so a single odd response cannot wreck the budget.
     */
    public void calibrate(String model, int rawEstimate, int actualPromptTokens) {
        String key = model == null ? "" : model.strip();
        if (key.isEmpty() || rawEstimate < MIN_OBSERVABLE_PROMPT || actualPromptTokens <= 0) return;
        activeModel = key;
        double observed = clamp((double) actualPromptTokens / rawEstimate);
        double updated = clamp(factor(key) * (1.0 - SMOOTHING) + observed * SMOOTHING);
        factors.put(key, updated);
        if (settings != null) {
            settings.putSetting(SETTING_PREFIX + key, Double.toString(updated));
        }
    }

    public double factor(String model) {
        String key = model == null ? "" : model.strip();
        if (key.isEmpty()) return 1.0;
        Double cached = factors.get(key);
        if (cached != null) return cached;
        double stored = readStoredFactor(key);
        factors.put(key, stored);
        return stored;
    }

    private double readStoredFactor(String model) {
        if (settings == null) return 1.0;
        try {
            return settings.getSetting(SETTING_PREFIX + model)
                    .map(Double::parseDouble).map(TokenEstimator::clamp).orElse(1.0);
        } catch (RuntimeException unusable) {
            // A corrupted setting must never block a question; fall back to the uncalibrated rule.
            return 1.0;
        }
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 1.0;
        return Math.min(MAX_FACTOR, Math.max(MIN_FACTOR, value));
    }

    private static boolean isWideScript(int codePoint) {
        try {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            return script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL;
        } catch (IllegalArgumentException notACodePoint) {
            return false;
        }
    }
}
