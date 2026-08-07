package com.simplerag.application.conversation;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves conversational references in a follow-up question before the first local search.
 *
 * <p>The adaptive loop can only generate better queries <em>after</em> it has seen evidence, and the
 * remote planner is not consulted until the user has authorised the send. That left the very first
 * search of a follow-up turn running on the raw question — "它在哪？", "这个怎么改" — which carries
 * almost no retrievable terms and often returns nothing, so the loop had no evidence to reason about.
 *
 * <p>This resolver is deliberately local, deterministic and rule-based: it runs before authorisation,
 * so it must not call a model. It only prepends the subject of the most recent user turn, which is
 * enough to make a pronoun-only follow-up retrievable.
 */
public final class ConversationQueryResolver {
    /** Beyond this length a question carries enough of its own terms to retrieve on. */
    private static final int SHORT_QUESTION_CHARS = 24;
    private static final int MAX_RESOLVED_CHARS = 300;

    /** Pronouns and elliptical openers that only make sense against the previous turn. */
    private static final Pattern REFERENTIAL = Pattern.compile(
            "(?i)(它|他|她|这个|那个|这些|那些|此处|这里|那里|上面|前面|刚才|该功能|该方法|该类|该配置|该文件"
            + "|\\bit\\b|\\bthis\\b|\\bthat\\b|\\bthese\\b|\\bthose\\b|\\bthere\\b|\\bthe same\\b|\\babove\\b)");

    private ConversationQueryResolver() { }

    /**
     * Returns the text to use for the first search. Falls back to the original question whenever
     * there is nothing useful to borrow, so behaviour is unchanged for self-contained questions.
     */
    public static String resolve(String question, List<ChatMessage> history) {
        if (question == null) return "";
        String current = question.strip();
        if (current.isEmpty() || !needsContext(current) || history == null || history.isEmpty()) {
            return current;
        }
        String subject = lastUserSubject(history);
        if (subject.isEmpty()) return current;
        String resolved = subject + " " + current;
        return resolved.length() <= MAX_RESOLVED_CHARS ? resolved
                : resolved.substring(0, MAX_RESOLVED_CHARS).strip();
    }

    /** True when the question is too short to retrieve on, or leans on a reference. */
    private static boolean needsContext(String question) {
        return question.codePointCount(0, question.length()) <= SHORT_QUESTION_CHARS
                || REFERENTIAL.matcher(question).find();
    }

    private static String lastUserSubject(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if (message == null || message.role() != ChatMessage.Role.USER) continue;
            String content = message.content().strip();
            if (!content.isEmpty()) return content;
        }
        return "";
    }
}
