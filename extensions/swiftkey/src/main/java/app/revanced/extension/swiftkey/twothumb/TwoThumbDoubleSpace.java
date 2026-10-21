package app.revanced.extension.swiftkey.twothumb;

/**
 * SwiftKey's "double space for period" (pref_quick_period_key) for two-thumb words (no Android
 * types, for the JVM checks). SwiftKey never sees the space the glue commits a word with, so its
 * own quick period cannot fire on the space after it. The glue keeps the last word it committed
 * with its own space (where it starts, the word plus the space, and the space press's time). When
 * the very next key is space, within [WINDOW_MS], and the text before the cursor is still exactly
 * that commit, the trailing space becomes [PERIOD]. Any other key, a cursor move or an edit the
 * glue did not make in between forgets it ([clear]).
 *
 * SwiftKey's own rule (ac0.f) is untimed: the char before the cursor is a space and the one before
 * that is not a space or one of ".。!?\n\t,:;-". The window is ours, so a slow second space after a
 * pause stays a plain space.
 */
public final class TwoThumbDoubleSpace {
    /** Longest gap between the two space presses (SwiftKey has none of its own). */
    public static final long WINDOW_MS = 500;
    /** What the trailing space becomes (SwiftKey's q40.b period "." plus its space). */
    public static final String PERIOD = ". ";
    private static final String NO_PERIOD_AFTER = ".。!?\n\t,:;-";

    private int start = -1;
    private String committed;
    private long time;

    /** The glue committed [committed] (a word and its space) at absolute offset [start] (-1
     * unknown), with the space pressed at [time] (ms, uptime). */
    public void record(int start, String committed, long time) {
        clear();
        if (committed == null || committed.length() < 2 || committed.charAt(committed.length() - 1) != ' ') return;
        char last = committed.charAt(committed.length() - 2);
        if (Character.isWhitespace(last) || NO_PERIOD_AFTER.indexOf(last) >= 0) return;
        this.start = start;
        this.committed = committed;
        this.time = time;
    }

    public void clear() {
        start = -1;
        committed = null;
        time = 0;
    }

    public boolean pending() {
        return committed != null;
    }

    /** How many chars before the cursor the commit takes (0 when nothing is pending). */
    public int length() {
        return committed == null ? 0 : committed.length();
    }

    /**
     * At a space press at [time]: whether it is the double space, given the pref ([enabled]), the
     * cursor ([cursor] absolute, -1 unknown), the text before it ([before], at least [length]
     * chars) and whether text is selected. Forgets the commit either way, so a third space is an
     * ordinary one. A double space also forgets [undo] (the backspace after it is ordinary).
     */
    public boolean take(boolean enabled, long time, int cursor, CharSequence before, boolean selection, TwoThumbUndo undo) {
        String c = committed;
        int s = start;
        long t = this.time;
        clear();
        if (!enabled || c == null || selection || before == null || before.length() < c.length()) return false;
        if (time < t || time - t > WINDOW_MS) return false;
        if (!c.contentEquals(before.subSequence(before.length() - c.length(), before.length()))) return false;
        if (s >= 0 && cursor >= 0 && cursor != s + c.length()) return false;
        if (undo != null) undo.clear();
        return true;
    }
}
