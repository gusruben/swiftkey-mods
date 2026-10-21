package app.revanced.extension.swiftkey.twothumb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apostrophe tiebreak for two-thumb words (no Android types, so the JVM checks can run it).
 * Nintype often ranks "its" over "it's", "cant" over "can't"; SwiftKey's language model knows
 * the sentence. When the open word's top and another alternative are the same word once
 * apostrophes and case are ignored, or the top is the plain form of a common contraction the
 * list lacks, both are scored in the sentence context ({@link Scorer}, Fluency on its own thread)
 * and the other one becomes the top only when it clearly wins. Scores are cached per
 * (context, pair), so the word's later updates never query again.
 */
public final class ApostropheTiebreak {
    /** A word's probability in the context, or a negative value when the model has no opinion. */
    public interface Scorer {
        double score(String context, String word) throws Exception;
    }

    /** An in-list variant must score this many times the top's probability to take its place. */
    public static final double MARGIN = 1.5;
    /** A contraction the list lacks needs a wider margin, and at least this probability. */
    public static final double ADD_MARGIN = 2.0;
    public static final double MIN_KNOWN = 1e-4;
    private static final int CACHE_SIZE = 128;

    private static final String[] CONTRACTIONS = {
            "it's", "can't", "won't", "don't", "i'll", "i'd", "i'm", "i've", "we're", "we'll",
            "she'll", "he'll", "you're", "they're", "let's", "what's", "that's", "isn't", "doesn't",
            "didn't", "wasn't", "aren't", "couldn't", "wouldn't", "shouldn't",
    };
    private static final Map<String, String> BY_PLAIN = new HashMap<>();

    static {
        for (String c : CONTRACTIONS) BY_PLAIN.put(key(c), c);
    }

    /** What to ask: the top word (index 0) against [other], at [otherIndex] or added (-1). */
    public static final class Question {
        public final String top, other;
        public final int otherIndex;

        Question(String top, String other, int otherIndex) {
            this.top = top;
            this.other = other;
            this.otherIndex = otherIndex;
        }

        public boolean added() {
            return otherIndex < 0;
        }
    }

    public static final class Decision {
        public final Question question;
        /** Probabilities (negative: absent). */
        public final double topScore, otherScore;
        public final boolean swap, cached;
        public final String why;

        Decision(Question q, double topScore, double otherScore, boolean swap, boolean cached, String why) {
            this.question = q;
            this.topScore = topScore;
            this.otherScore = otherScore;
            this.swap = swap;
            this.cached = cached;
            this.why = why;
        }
    }

    private final Scorer scorer;
    private final LinkedHashMap<String, double[]> cache = new LinkedHashMap<String, double[]>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    /** Fluency queries made (for the checks and the log). */
    public int queries;

    public ApostropheTiebreak(Scorer scorer) {
        this.scorer = scorer;
    }

    /** [w] without apostrophes (' and U+2019), lower case. */
    public static String key(String w) {
        StringBuilder b = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c != '\'' && c != '’') b.append(c);
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }

    /** Lower case with U+2019 folded to ', for comparing spellings. */
    public static String norm(String w) {
        return w.replace('’', '\'').toLowerCase(Locale.ROOT);
    }

    private static boolean hasApostrophe(String w) {
        return w.indexOf('\'') >= 0 || w.indexOf('’') >= 0;
    }

    /**
     * The question for a bar's raw words ([raw][0] is the top), or null when there is none.
     * [literal]: the keys typed (null: none); [literalForced]: the user forced it (a backspace in
     * the word made it the top), and then the top is never overridden.
     */
    public static Question question(String[] raw, String literal, boolean literalForced) {
        if (raw.length == 0) return null;
        String top = raw[0];
        if (top == null || top.length() < 2) return null;
        if (literalForced && literal != null && norm(literal).equals(norm(top))) return null;
        String k = key(top);
        for (int i = 1; i < raw.length; i++) {
            String w = raw[i];
            if (w == null || norm(w).equals(norm(top))) continue;
            if (hasApostrophe(w) == hasApostrophe(top)) continue;
            if (key(w).equals(k)) return new Question(top, w, i);
        }
        if (!hasApostrophe(top)) {
            String c = BY_PLAIN.get(k);
            if (c != null) return new Question(top, c, -1);
        }
        return null;
    }

    /** The cached decision for [q] in [context], or null when it was never scored. */
    public synchronized Decision peek(String context, Question q) {
        double[] s = cache.get(cacheKey(context, q));
        if (s == null) return null;
        boolean flip = flipped(q);
        return decide(q, flip ? s[1] : s[0], flip ? s[0] : s[1], true);
    }

    /** Scores [q] in [context] (cached) and decides. On Fluency's thread when not cached. */
    public synchronized Decision decide(String context, Question q) throws Exception {
        String key = cacheKey(context, q);
        double[] s = cache.get(key);
        boolean flip = flipped(q);
        if (s != null) return decide(q, flip ? s[1] : s[0], flip ? s[0] : s[1], true);
        queries++;
        double top = scorer.score(context, q.top);
        double other = scorer.score(context, q.other);
        cache.put(key, flip ? new double[]{other, top} : new double[]{top, other});
        return decide(q, top, other, false);
    }

    /** The cache holds a pair's scores in the order of its sorted spellings. */
    private static boolean flipped(Question q) {
        return norm(q.top).compareTo(norm(q.other)) > 0;
    }

    private static String cacheKey(String context, Question q) {
        // the pair, unordered: "its" over "it's" and "it's" over "its" share their scores
        boolean flip = flipped(q);
        String a = norm(q.top), b = norm(q.other);
        return context + '\u0000' + (flip ? b : a) + '\u0000' + (flip ? a : b);
    }

    private static Decision decide(Question q, double top, double other, boolean cached) {
        if (top <= 0 || other <= 0) return new Decision(q, top, other, false, cached, "no opinion");
        if (q.added()) {
            if (other < MIN_KNOWN) return new Decision(q, top, other, false, cached, "contraction unknown");
            boolean swap = other >= top * ADD_MARGIN;
            return new Decision(q, top, other, swap, cached, swap ? "contraction added" : "kept");
        }
        boolean swap = other >= top * MARGIN;
        return new Decision(q, top, other, swap, cached, swap ? "swapped" : "kept");
    }

    /**
     * [items] in the tiebreak's order: the question's other word first (moved from its index, or
     * [added] put in front), everything else in its order after it, at most [max].
     */
    public static <T> List<T> reorder(List<T> items, Question q, T added, int max) {
        List<T> out = new ArrayList<>(items.size() + 1);
        if (q.added()) {
            out.add(added);
            out.addAll(items);
        } else {
            out.add(items.get(q.otherIndex));
            for (int i = 0; i < items.size(); i++) if (i != q.otherIndex) out.add(items.get(i));
        }
        while (out.size() > max) out.remove(out.size() - 1);
        return out;
    }
}
