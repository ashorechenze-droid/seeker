package com.simplerag.application.conversation;

import com.simplerag.application.port.out.SettingsRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {
    @Test
    void chineseIsNoLongerCountedAsIfItWereEnglish() {
        String chinese = "本地知识库问答助手只能根据本轮提供的检索资料陈述事实";
        int charsOverFour = (chinese.length() + 3) / 4;

        int raw = TokenEstimator.rawTokens(chinese);

        // The old char/4 rule returned ~7 tokens for 26 ideographs; the real cost is several times that.
        assertEquals(chinese.length(), raw, "each ideograph should count as roughly one token");
        assertTrue(raw > charsOverFour * 3, "char/4 undercounted Chinese by more than 3x: " + charsOverFour);
    }

    @Test
    void latinTextStaysAtRoughlyFourCharactersPerToken() {
        String latin = "the quick brown fox jumps over the lazy dog again and again";

        int raw = TokenEstimator.rawTokens(latin);

        assertEquals((int) Math.ceil(latin.length() / 4.0), raw);
    }

    @Test
    void realUsageMovesTheEstimateTowardTheProvidersTokenizer() {
        InMemorySettings settings = new InMemorySettings();
        TokenEstimator estimator = new TokenEstimator(settings);
        List<ChatMessage> history = List.of(ChatMessage.user("x".repeat(4_000)));
        int before = estimator.estimate(history);

        // The endpoint reports twice what the heuristic guessed; repeated reports converge on it.
        for (int round = 0; round < 20; round++) {
            estimator.calibrate("qwen-max", 1_000, 2_000);
        }

        assertEquals(1.0, new TokenEstimator().factor("qwen-max"), 1e-9);
        assertEquals(2.0, estimator.factor("qwen-max"), 0.05);
        assertTrue(estimator.estimate(history) > before,
                "a model that bills more per character must shrink the history budget");
        assertTrue(settings.values.containsKey("tokens.calibration.qwen-max"),
                "the calibration should survive a restart");
    }

    @Test
    void implausibleReportsCannotWreckTheBudget() {
        TokenEstimator estimator = new TokenEstimator();

        for (int round = 0; round < 50; round++) {
            estimator.calibrate("broken", 1_000, 10_000_000);
        }

        // The smoothed factor approaches the ceiling asymptotically and must never cross it.
        assertTrue(estimator.factor("broken") <= 4.0, "the factor must stay clamped");
        assertEquals(4.0, estimator.factor("broken"), 1e-6);
    }

    @Test
    void unusableObservationsAreIgnored() {
        TokenEstimator estimator = new TokenEstimator();

        estimator.calibrate("m", 10, 5_000);   // prompt too small to measure reliably
        estimator.calibrate("m", 1_000, 0);    // provider reported nothing
        estimator.calibrate("", 1_000, 2_000); // no model to attribute it to

        assertEquals(1.0, estimator.factor("m"), 1e-9);
    }

    private static final class InMemorySettings implements SettingsRepository {
        private final Map<String, String> values = new HashMap<>();

        @Override public Optional<String> getSetting(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void putSetting(String key, String value) { values.put(key, value); }
    }
}
