package app.revanced.extension.swiftkey.twothumb;

/**
 * Two-thumb mode's press decisions, kept free of Android types so nintype-glue's JVM check
 * (PressCheck) runs them. UI thread only.
 * <ul>
 *   <li><b>Letter long-press:</b> a letter pointer that went down with no other pointer down, and
 *   stays within tap slop with no other pointer going down until SwiftKey's long-press delay has
 *   passed, becomes SwiftKey's long-press. A thumb resting while the other one swipes is ordinary
 *   Nintype input: any other down cancels it.</li>
 *   <li><b>Backspace on an open word:</b> a tap is the engine's backspace (at its lift). Once it
 *   moves past tap slop as the only pointer down (SwiftKey's swipe detector only reads one-pointer
 *   events), it is handed to SwiftKey for its swipe-to-delete, when that setting is on.</li>
 *   <li><b>Backspace hold on an open word (twothumb14):</b> a backspace that went down alone and
 *   stays within slop, with no other down, for SwiftKey's long-press delay is a hold. That delay is
 *   when SwiftKey's own held backspace deletes the last word; its repeat then starts at the delay +
 *   500 ms and runs every max(250 - 14 i, 120) ms ({@link #repeatInterval}). The whole word goes
 *   and the pointer is handed to SwiftKey, its timers shortened by the time already held
 *   ({@link #handoverDelay}), so its hold goes on from there.</li>
 * </ul>
 */
final class TwoThumbPress {
    static final int NONE = -1;

    private final float slop;

    private int lpId = NONE;
    private long lpDue;
    private float lpX, lpY;

    private int bsId = NONE;
    private float bsX, bsY;
    private boolean bsSwipe;
    private boolean bsSlid;
    /** When the backspace becomes a hold, or -1 when it can't. */
    private long bsHoldDue = -1;

    /** SwiftKey's backspace repeat (fc0.j0: hc0.m(long-press delay + 500, 1)): first repeat after
     * the long-press delay plus this. */
    static final long REPEAT_START_EXTRA = 500;

    /** SwiftKey's repeat interval after repeat [i] (hc0.m.w, factor 1 outside touch exploration). */
    static long repeatInterval(int i) {
        return Math.max(250 - i * 14, 120);
    }

    /** SwiftKey's first backspace repeat for its long-press delay [longPress]. */
    static long repeatStart(long longPress) {
        return longPress + REPEAT_START_EXTRA;
    }

    /** A delay SwiftKey posts for a down handed over after [held] ms: only what is left of it. */
    static long handoverDelay(long delay, long held) {
        return held < 0 ? delay : Math.max(1, delay - held);
    }

    TwoThumbPress(float slop) {
        this.slop = slop;
    }

    // ---- letter long-press ----

    /**
     * A letter pointer went down; [others] = pointers already down.
     *
     * @return When its long-press is due (same clock as [time]), or -1 when it can't become one.
     */
    long letterDown(int id, long time, float x, float y, int others, long timeout) {
        lpId = NONE;
        if (id != bsId) bsHoldDue = -1;
        if (others > 0 || timeout <= 0) return -1;
        lpId = id;
        lpDue = time + timeout;
        lpX = x;
        lpY = y;
        return lpDue;
    }

    /** Any other pointer went down (a key, a letter, the other thumb): no long-press. */
    void otherDown(int id) {
        if (id != lpId) lpId = NONE;
        if (id != bsId) bsHoldDue = -1;
    }

    /** A sample of pointer [id]. */
    void moved(int id, float x, float y) {
        if (id == lpId && Math.hypot(x - lpX, y - lpY) > slop) lpId = NONE;
        if (id == bsId && Math.hypot(x - bsX, y - bsY) > slop) {
            bsSlid = true;
            bsHoldDue = -1;
        }
    }

    /** Pointer [id] lifted or was cancelled. */
    void up(int id) {
        if (id == lpId) lpId = NONE;
        if (id == bsId) bsHoldDue = -1;
    }

    /** The long-press timer ran at [now]: the pointer that becomes a long-press, or NONE. */
    int fireLongPress(long now) {
        if (lpId == NONE || now < lpDue - 5) return NONE;
        int id = lpId;
        lpId = NONE;
        return id;
    }

    boolean longPressArmed(int id) {
        return lpId == id;
    }

    // ---- backspace on an open word ----

    /**
     * Backspace went down on an open word. [swipeDelete]: SwiftKey's swipe-to-delete is on;
     * [others]: pointers already down; [holdDelay]: SwiftKey's long-press delay.
     *
     * @return When it becomes a hold (same clock as [time]), or -1 when it can't.
     */
    long backspaceDown(int id, long time, float x, float y, int others, boolean swipeDelete, long holdDelay) {
        bsId = id;
        bsX = x;
        bsY = y;
        bsSwipe = swipeDelete;
        bsSlid = false;
        bsHoldDue = others > 0 || holdDelay <= 0 ? -1 : time + holdDelay;
        return bsHoldDue;
    }

    /**
     * The hold timer ran at [now]: the backspace pointer that is now a hold (the word goes, the
     * pointer goes to SwiftKey), or NONE.
     */
    int fireHold(long now) {
        if (bsId == NONE || bsHoldDue < 0 || now < bsHoldDue - 5) return NONE;
        int id = bsId;
        bsId = NONE;
        bsHoldDue = -1;
        return id;
    }

    /**
     * After the samples of a move event were passed to [moved].
     *
     * @return True when the backspace pointer [id] goes to SwiftKey now ([pointers] = pointers in
     * the event).
     */
    boolean backspaceSlides(int id, int pointers) {
        return id == bsId && bsSlid && bsSwipe && pointers == 1;
    }

    /** The backspace pointer was handed to SwiftKey. */
    void backspaceHandedOff(int id) {
        if (id == bsId) {
            bsId = NONE;
            bsHoldDue = -1;
        }
    }

    /** @return True when the lift of [id] is a backspace tap (the engine's backspace). */
    boolean backspaceUp(int id) {
        if (id != bsId) return false;
        bsId = NONE;
        bsHoldDue = -1;
        return true;
    }
}
