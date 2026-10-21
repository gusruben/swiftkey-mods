package app.revanced.extension.swiftkey.twothumb;

import java.util.ArrayList;
import java.util.List;

/**
 * The order of the two-thumb bar's words (no Android types, so the JVM checks can run it).
 * SwiftKey's three-slot bar (ob0.p.a) puts rank 0 in the middle slot and fills the others left to
 * right with ranks 1 and 2, so the literal goes in at rank 1: the top word in the middle, the
 * exact keys typed on the left, Nintype's next alternative on the right.
 */
public final class TwoThumbBarOrder {
    private TwoThumbBarOrder() {
    }

    /**
     * [top]: Nintype's current word; [literal]: the keys typed (null: none); [candidates]: the
     * search's ranked words. At most [max] words, no case-insensitive duplicates, single letters
     * only as the top word or the literal, words of letters, apostrophes and hyphens only.
     */
    public static List<String> order(String top, String literal, String[] candidates, int max) {
        List<String> raw = new ArrayList<>();
        if (isBarWord(top)) raw.add(top);
        if (isBarWord(literal) && !contains(raw, literal)) raw.add(literal);
        for (String w : candidates) {
            if (raw.size() >= max) break;
            if (!isBarWord(w) || (w.length() == 1 && !raw.isEmpty())) continue;
            if (!contains(raw, w)) raw.add(w);
        }
        while (raw.size() > max) raw.remove(raw.size() - 1);
        return raw;
    }

    private static boolean contains(List<String> list, String w) {
        for (String have : list) if (have.equalsIgnoreCase(w)) return true;
        return false;
    }

    public static boolean isBarWord(String w) {
        if (w == null || w.isEmpty()) return false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!Character.isLetter(c) && c != '\'' && c != '’' && c != '-') return false;
        }
        return true;
    }
}
