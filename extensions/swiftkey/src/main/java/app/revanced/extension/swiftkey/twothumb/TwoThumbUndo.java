package app.revanced.extension.swiftkey.twothumb;

/**
 * SwiftKey's "undo autocorrect on backspace" for two-thumb words (no Android types, for the JVM
 * checks). SwiftKey does not know the corrections the glue writes, so the glue keeps its own: the
 * last two-thumb commit (where it starts, the text committed with its trailing space, and the
 * literal the user typed). When the very next key is backspace and the text before the cursor is
 * still exactly that commit, the commit is replaced with the literal, without the space. Any other
 * key, a cursor move or an edit the glue did not make in between forgets it ([clear]).
 */
public final class TwoThumbUndo {
    private int start = -1;
    private String committed;
    private String literal;

    /** A two-thumb commit of [committed] (word plus separator) at absolute offset [start] (-1
     * unknown), typed as [literal]. Nothing to undo when the word is the literal itself. */
    public void record(int start, String committed, String literal) {
        clear();
        if (committed == null || literal == null || literal.isEmpty()) return;
        String word = committed.trim();
        if (word.isEmpty() || word.equalsIgnoreCase(literal)) return;
        this.start = start;
        this.committed = committed;
        this.literal = literal;
    }

    public void clear() {
        start = -1;
        committed = null;
        literal = null;
    }

    public boolean pending() {
        return committed != null;
    }

    /** How many chars before the cursor the commit takes (0 when nothing is pending). */
    public int length() {
        return committed == null ? 0 : committed.length();
    }

    /**
     * At the backspace: the literal to put in place of the last [length] chars when the editor
     * still shows the commit right before the cursor ([before], at least [length] chars; [cursor]
     * absolute, -1 unknown; [selection] = text is selected), else null. Forgets the commit either
     * way, so a second backspace is an ordinary one.
     */
    public String revert(int cursor, CharSequence before, boolean selection) {
        String c = committed, lit = literal;
        int s = start;
        clear();
        if (c == null || selection || before == null || before.length() < c.length()) return null;
        if (!c.contentEquals(before.subSequence(before.length() - c.length(), before.length()))) return null;
        if (s >= 0 && cursor >= 0 && cursor != s + c.length()) return null;
        return lit;
    }

    public String committed() {
        return committed;
    }
}
