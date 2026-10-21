package app.revanced.extension.swiftkey.twothumb;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.swiftkey.settings.ModSettings;
import app.revanced.extension.swiftkey.toolbar.ToolbarItems;
import app.revanced.extension.swiftkey.typing.CapitalIPatch;
import app.revanced.extension.swiftkey.twothumb.nintype.LiveSession;
import app.revanced.extension.swiftkey.twothumb.nintype.NintypeDecoder;
import app.revanced.extension.swiftkey.twothumb.nintype.TouchLog;
import nintype.engine.pipeline.BackspaceResult;
import nintype.engine.pipeline.Candidate;
import app.revanced.extension.swiftkey.visual.KeyPressRipple;
import app.revanced.extension.swiftkey.visual.TouchTrail;
import nintype.engine.geometry.KeyboardModel;

/**
 * Two-thumb mode: Nintype's own input model on SwiftKey's keyboard, switched on and off from the
 * toolbar (or Mods).
 * <ul>
 *   <li>Every pointer that goes down on a letter key belongs to Nintype for its whole life (taps,
 *   swipes, either thumb, any overlap). SwiftKey never sees it: the main keyboard view's dispatch
 *   gets an event with SwiftKey's own pointers only, or nothing.</li>
 *   <li>Other keys stay SwiftKey's. Before one of them acts, the current word is committed
 *   (space, punctuation, enter, symbols...). A backspace tap on an open word is Nintype's: it
 *   removes the word's last touch group (a tap, a swipe, a two-thumb burst) and re-decodes the
 *   rest; once the word is empty, backspace is SwiftKey's again. A backspace that slides closes the
 *   word as typed and goes to SwiftKey (its swipe-to-delete); one held still deletes the whole
 *   word and goes to SwiftKey, whose held backspace goes on deleting words. Shift capitalises the
 *   next word.</li>
 *   <li>A letter held still on its own for SwiftKey's long-press delay leaves the word and becomes
 *   SwiftKey's long-press (its symbol popup); see {@link TwoThumbPress} for the rules.</li>
 *   <li>Any edit the glue did not make (a suggestion-bar tap, a cursor move, the app changing the
 *   text) closes the open word without writing: the next touch starts a fresh word.</li>
 *   <li>Nintype builds the word across lifts in a long-lived engine on the decoder thread
 *   ({@link LiveSession}); its current word is the editor's composing text.</li>
 *   <li>Feedback SwiftKey can't give for touches it doesn't see: the key press ripple on the key a
 *   pointer lands on, and a trail for every pointer that moves (each with its own Mods switch).</li>
 *   <li>While a word is open, SwiftKey's suggestion bar shows Nintype's alternatives (fed in as
 *   SwiftKey's own candidate result, {@link #barPredictions}). A tap on one is ours
 *   ({@link #onCandidateSelected}): the word is replaced through the anchored write, never through
 *   SwiftKey's replace.</li>
 * </ul>
 * Off = this class passes every event through untouched.
 */
@SuppressWarnings("unused")
public final class TwoThumbMode {
    private static final String TAG = "SwiftKeyMods";

    /** Toolbar button id (stock ids stop below 50, Capital I is 90). */
    public static final int TOOLBAR_ITEM_ID = 91;

    private static final int MAX_POINTERS = 32;
    private static final int NONE = 0, ENGINE = 1, SWIFTKEY = 2, SWALLOWED = 3;
    /** Backspace pressed on an open word: a tap is the engine's backspace (at its lift), a slide or a
     * hold goes to SwiftKey. */
    private static final int BACKSPACE = 4;
    private static final long COMMIT_WAIT_MS = 150;
    /** A backspace re-decodes the rest of the word, so it may take longer than a commit. */
    private static final long BACKSPACE_WAIT_MS = 400;
    private static final float DENSITY = Resources.getSystem().getDisplayMetrics().density;

    // ---- UI thread ----
    private static final int[] owner = new int[MAX_POINTERS];
    private static final String[] downLabel = new String[MAX_POINTERS];
    private static final long[] downTime = new long[MAX_POINTERS];
    private static final float[] downX = new float[MAX_POINTERS], downY = new float[MAX_POINTERS];
    private static final float[] lastX = new float[MAX_POINTERS], lastY = new float[MAX_POINTERS];
    private static final float[] lastPressure = new float[MAX_POINTERS], lastSize = new float[MAX_POINTERS];
    private static final float[] maxMove = new float[MAX_POINTERS];
    private static final int[] samples = new int[MAX_POINTERS];
    private static final Handler ui = new Handler(Looper.getMainLooper());
    /** The current gesture (ACTION_DOWN to the last up) started in two-thumb mode. */
    private static boolean gestureActive;
    /** Nintype holds a word the editor shows as composing text. */
    private static boolean wordOpen;
    /** Bumped at every commit or clear; editor updates from an older word are dropped. */
    private static volatile int wordGen;
    private static volatile boolean shiftNext;
    /**
     * Where the open word sits in the editor (absolute offset, -1 unknown) and how long it is.
     * Every write names this region explicitly: SwiftKey's own editor mirror never sees our writes,
     * and its selection-change handler (cf0.c) keeps re-placing the composing region over what its
     * stale mirror thinks the current word is, so "the current composing region" is not ours.
     */
    private static int anchor = -1;
    private static int shown;
    private static boolean anchored;
    /** The text the glue last wrote as the open word (what the editor must still show before the
     * cursor for the word to be ours). */
    private static String written = "";

    // ---- decoder thread ----
    private static LiveSession session;
    private static volatile String display = "";
    /** Nintype's current word as the engine spells it (before casing). */
    private static volatile String liquidRaw;
    /** The open word's literal (the keys typed), cased like the composing text; null: none. */
    private static volatile String literalShown;
    /** The last two-thumb commit, for SwiftKey's undo-autocorrect-on-backspace (UI thread). */
    private static final TwoThumbUndo undo = new TwoThumbUndo();
    /** The last word our space committed, for SwiftKey's double-space period (UI thread). */
    private static final TwoThumbDoubleSpace period = new TwoThumbDoubleSpace();
    /** Double space for period after a two-thumb word: parked for now. */
    private static final boolean QUICK_PERIOD = false;
    /** The time of the latest pointer down (ms, uptime), for the double-space window. */
    private static long lastDownTime;
    private static volatile boolean sentenceStart;
    private static boolean ticking;

    private TwoThumbMode() {
    }

    // =========================================================================================
    // Mode switch (toolbar + Mods)

    private static void setMode(boolean on) {
        Context context = context();
        if (context == null) return;
        if (!on) commitWord("mode off", null);
        undo.clear();
        period.clear();
        ModSettings.preferences(context).edit().putBoolean(ModSettings.TWO_THUMB_MODE, on).apply();
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: mode " + (on ? "on" : "off"));
        Toast.makeText(context, on ? "Two-finger swipe mode on" : "Two-finger swipe mode off", Toast.LENGTH_SHORT).show();
    }

    private static Context context() {
        InputMethodService service = TwoThumbSwipe.service();
        return service == null ? null : service.getApplicationContext();
    }

    private static int iconOn, iconOff;

    private static int icon() {
        Context context = context();
        if (iconOn == 0 && context != null) {
            iconOn = context.getResources().getIdentifier("revanced_two_thumb_on", "drawable", context.getPackageName());
            iconOff = context.getResources().getIdentifier("revanced_two_thumb_off", "drawable", context.getPackageName());
        }
        int icon = ModSettings.twoThumbMode() ? iconOn : iconOff;
        return icon != 0 ? icon : android.R.drawable.ic_menu_edit;
    }

    /** Called by ToolbarItems from the toolbar item factory. */
    public static Object createToolbarItem(Class<?> itemClass) {
        return ToolbarItems.toggleItem(itemClass, TOOLBAR_ITEM_ID, TwoThumbMode::icon,
                () -> ModSettings.twoThumbMode() ? "Two-finger swipe mode on" : "Two-finger swipe mode off",
                "Two-finger swipe", () -> setMode(!ModSettings.twoThumbMode()));
    }

    static void onKeyboardHidden() {
        if (wordOpen) commitWord("keyboard hidden", null);
        undo.clear();
        period.clear();
        // a hold must never reach SwiftKey without its lift (its repeat would run on)
        ui.removeCallbacks(holdTimer);
        bsDown = null;
    }

    // =========================================================================================
    // Touch routing (UI thread)

    /** Whether two-thumb mode takes this event (decided per gesture, at its first down). */
    static boolean handles(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            gestureActive = ModSettings.twoThumbMode();
            if (!gestureActive && wordOpen) commitWord("mode off", null);
            if (!gestureActive) {
                undo.clear();
                period.clear();
            }
        }
        return gestureActive;
    }

    /**
     * Routes one event: Nintype's pointers go to the engine, the rest come back for SwiftKey.
     *
     * @return The event SwiftKey dispatches (its own pointers only), or null when it has none.
     */
    static MotionEvent filter(View view, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            Arrays.fill(owner, NONE);
            TwoThumbSwipe.rebuildLayout(view);
        }
        KeyboardModel model = TwoThumbSwipe.model();
        NintypeDecoder decoder = TwoThumbSwipe.decoder();
        int actionIndex = event.getActionIndex();
        int actionId = event.getPointerId(actionIndex);
        boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
        boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP;

        if (down && actionId < MAX_POINTERS) {
            float x = event.getX(actionIndex), y = event.getY(actionIndex);
            int key = TwoThumbSwipe.keyAt(x, y);
            String label = TwoThumbSwipe.keyLabel(key);
            downLabel[actionId] = label;
            downTime[actionId] = event.getEventTime();
            downX[actionId] = lastX[actionId] = x;
            downY[actionId] = lastY[actionId] = y;
            maxMove[actionId] = 0;
            samples[actionId] = 0;
            lastPressure[actionId] = event.getPressure(actionIndex);
            lastSize[actionId] = event.getSize(actionIndex);
            if (wordOpen) checkWordIntact();
            TwoThumbPress p = press(view);
            boolean backspaceKey = label != null && (label.contains("delete") || label.contains("backspace"));
            lastDownTime = event.getEventTime();
            // double space for period: only a space right after our own space's commit
            boolean quickPeriod = QUICK_PERIOD && !wordOpen && "space".equals(label) && event.getPointerCount() == 1
                    && period.pending() && doubleSpace(lastDownTime);
            if (!quickPeriod) period.clear();
            // undo autocorrect: only a backspace right after a two-thumb commit reverts it
            if (!backspaceKey || wordOpen || event.getPointerCount() > 1) undo.clear();
            if (quickPeriod) {
                // the whole press is ours: SwiftKey never saw the first space either
                owner[actionId] = SWALLOWED;
                keyFeedback(view, SOUND_SPACE);
            } else if (model != null && decoder != null && isLetter(label)) {
                owner[actionId] = ENGINE;
                if (!wordOpen) startWord(model, decoder);
                KeyPressRipple.rippleKey(view, TwoThumbSwipe.keyObject(key));
                keyFeedback(view, SOUND_KEY);
                TouchTrail.add(view, actionId, x, y, true);
                armLongPress(p, event, actionIndex, actionId, x, y);
            } else if (!wordOpen && backspaceKey && undo.pending() && revertCommit()) {
                // the whole press is ours: SwiftKey's delete (and its repeat) must not follow
                owner[actionId] = SWALLOWED;
                keyFeedback(view, SOUND_DELETE);
            } else if (wordOpen && backspaceKey) {
                p.otherDown(actionId);
                owner[actionId] = BACKSPACE;
                long due = p.backspaceDown(actionId, event.getEventTime(), x, y, event.getPointerCount() - 1,
                        swipeDelete(), longPressTimeout());
                bsCount = 0;
                armHold(event, due);
            } else if (wordOpen && "space".equals(label)) {
                // The word and its space go in together from here; SwiftKey's separator logic would
                // act on its stale view of the text.
                owner[actionId] = SWALLOWED;
                keyFeedback(view, SOUND_SPACE);
                commitWord("space", " ");
            } else {
                p.otherDown(actionId);
                owner[actionId] = SWIFTKEY;
                if (label != null && label.contains("shift")) {
                    if (!wordOpen) shiftNext = true;
                } else if (wordOpen && label != null) {
                    commitWord("before \"" + label + "\"", null);
                }
                // SwiftKey handles this key from its mirror of the editor, which has not seen our
                // writes yet when the key comes fast ("now" + "." gave "nowno."): bring it up to date.
                resyncSwiftKey(label);
            }
        }

        if (lastView.get() != view) lastView = new WeakReference<>(view);
        if (action == MotionEvent.ACTION_MOVE) backspaceMoves(view, event);

        feedEngine(view, event, action, actionIndex);

        if (up && actionId < MAX_POINTERS) {
            logTouch(actionId);
            if (owner[actionId] == ENGINE) TouchTrail.end(actionId);
            if (press != null) {
                press.up(actionId);
                if (owner[actionId] == BACKSPACE && press.backspaceUp(actionId)) {
                    if (wordOpen) backspaceWord(event.getEventTime(), -1);
                    else if (ModSettings.debug()) Log.i(TAG, "two-thumb: backspace lifted after the word closed, ignored");
                }
            }
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int id = event.getPointerId(i);
                if (id < MAX_POINTERS && owner[id] == ENGINE) TouchTrail.end(id);
                if (id < MAX_POINTERS && press != null) press.up(id);
            }
        }

        MotionEvent out = forSwiftKey(event, action, actionIndex, actionId);
        if (up && actionId < MAX_POINTERS) owner[actionId] = NONE;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gestureActive = ModSettings.twoThumbMode();
        return out;
    }

    private static boolean isLetter(String label) {
        if (label == null || label.length() != 1) return false;
        char c = label.charAt(0);
        return (c >= 'a' && c <= 'z') || c == '\'';
    }

    /** Engine pointers' samples (historical first) to the decoder thread, plus their trails. */
    private static void feedEngine(View view, MotionEvent event, int action, int actionIndex) {
        int count = event.getPointerCount();
        TouchLog chunk = null;
        int history = event.getHistorySize();
        for (int i = 0; i < count; i++) {
            int id = event.getPointerId(i);
            if (id >= MAX_POINTERS || owner[id] != ENGINE) continue;
            if (chunk == null) chunk = new TouchLog(4 * (history + 1));
            boolean isAction = i == actionIndex;
            int kind = 1;
            if (isAction && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)) kind = 0;
            if (isAction && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)) kind = 2;
            if (action == MotionEvent.ACTION_CANCEL) kind = 2;
            if (kind == 1) {
                for (int h = 0; h < history; h++) {
                    addSample(chunk, view, id, 1, event.getHistoricalX(i, h), event.getHistoricalY(i, h),
                            event.getHistoricalPressure(i, h), event.getHistoricalSize(i, h), event.getHistoricalEventTime(h));
                }
            }
            addSample(chunk, view, id, kind, event.getX(i), event.getY(i), event.getPressure(i), event.getSize(i),
                    event.getEventTime());
        }
        if (chunk == null || chunk.size == 0) return;
        final TouchLog batch = chunk;
        final int gen = wordGen;
        Handler h = TwoThumbSwipe.handler();
        if (h != null) h.post(() -> engineTouches(batch, gen));
    }

    private static void addSample(TouchLog chunk, View view, int id, int kind, float x, float y, float pressure,
                                  float size, long time) {
        if (kind == 1 && x == lastX[id] && y == lastY[id]) return;
        chunk.add(id, kind, x, y, pressure, size, time);
        samples[id]++;
        maxMove[id] = Math.max(maxMove[id], (float) Math.hypot(x - downX[id], y - downY[id]));
        lastX[id] = x;
        lastY[id] = y;
        lastPressure[id] = pressure;
        lastSize[id] = size;
        if (press != null) press.moved(id, x, y);
        if (kind == 1 && maxMove[id] > 6 * DENSITY) TouchTrail.add(view, id, x, y, false);
    }

    private static void logTouch(int id) {
        if (!ModSettings.debug()) return;
        String where = owner[id] == ENGINE ? "Nintype" : owner[id] == SWIFTKEY ? "SwiftKey"
                : owner[id] == SWALLOWED ? "swallowed" : owner[id] == BACKSPACE ? "Nintype backspace" : "nobody";
        Log.i(TAG, String.format(Locale.US, "two-thumb: touch p%d on \"%s\" -> %s, %d samples, moved %.0f dp, %d ms",
                id, downLabel[id], where, samples[id], maxMove[id] / DENSITY, SystemClock.uptimeMillis() - downTime[id]));
    }

    /** [event] with SwiftKey's pointers only (actions renumbered), the event itself when all are
     * SwiftKey's, or null when none are. */
    private static MotionEvent forSwiftKey(MotionEvent event, int action, int actionIndex, int actionId) {
        int count = event.getPointerCount();
        int mine = 0;
        for (int i = 0; i < count; i++) {
            int id = event.getPointerId(i);
            if (id < MAX_POINTERS && owner[id] == SWIFTKEY) mine++;
        }
        if (mine == count) return event;
        if (mine == 0) return null;

        boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
        boolean up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP;
        boolean actionIsMine = actionId < MAX_POINTERS && owner[actionId] == SWIFTKEY;
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[mine];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[mine];
        int[] source = new int[mine];
        int newIndex = 0;
        for (int i = 0, k = 0; i < count; i++) {
            int id = event.getPointerId(i);
            if (id >= MAX_POINTERS || owner[id] != SWIFTKEY) continue;
            if (i == actionIndex) newIndex = k;
            props[k] = new MotionEvent.PointerProperties();
            event.getPointerProperties(i, props[k]);
            source[k] = i;
            k++;
        }
        int newAction;
        if (action == MotionEvent.ACTION_CANCEL) newAction = MotionEvent.ACTION_CANCEL;
        else if (down && actionIsMine) newAction = mine == 1 ? MotionEvent.ACTION_DOWN
                : MotionEvent.ACTION_POINTER_DOWN | (newIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        else if (up && actionIsMine) newAction = mine == 1 ? MotionEvent.ACTION_UP
                : MotionEvent.ACTION_POINTER_UP | (newIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        else newAction = MotionEvent.ACTION_MOVE;

        int history = newAction == MotionEvent.ACTION_MOVE ? event.getHistorySize() : 0;
        for (int k = 0; k < mine; k++) {
            coords[k] = new MotionEvent.PointerCoords();
            if (history > 0) event.getHistoricalPointerCoords(source[k], 0, coords[k]);
            else event.getPointerCoords(source[k], coords[k]);
        }
        long firstTime = history > 0 ? event.getHistoricalEventTime(0) : event.getEventTime();
        MotionEvent out = MotionEvent.obtain(event.getDownTime(), firstTime, newAction, mine, props, coords,
                event.getMetaState(), event.getButtonState(), event.getXPrecision(), event.getYPrecision(),
                event.getDeviceId(), event.getEdgeFlags(), event.getSource(), event.getFlags());
        for (int h = 1; h <= history; h++) {
            for (int k = 0; k < mine; k++) {
                if (h < history) event.getHistoricalPointerCoords(source[k], h, coords[k]);
                else event.getPointerCoords(source[k], coords[k]);
            }
            out.addBatch(h < history ? event.getHistoricalEventTime(h) : event.getEventTime(), coords, event.getMetaState());
        }
        return out;
    }

    // =========================================================================================
    // Words (UI thread side)

    private static void startWord(KeyboardModel model, NintypeDecoder decoder) {
        wordOpen = true;
        anchored = false;
        anchor = -1;
        shown = 0;
        written = "";
        display = "";
        literalShown = null;
        final boolean shift = shiftNext;
        shiftNext = false;
        final int gen = wordGen;
        Handler h = TwoThumbSwipe.handler();
        if (h == null) return;
        h.post(() -> {
            try {
                if (session == null || session.getModel() != model) session = new LiveSession(decoder, model, listener);
                String before = TwoThumbSwipe.textBeforeCursor();
                barHistory.clear();
                wordBefore = before;
                backspacedInWord = false;
                sentenceStart = shift || (autoCaps() && isSentenceStart(before));
                session.setContext(before);
            } catch (Throwable t) {
                Log.e(TAG, "two-thumb: word start failed", t);
            }
        });
    }

    /**
     * Casing for the editor: Nintype capitalises the first word of its own sentences, but it never
     * sees what SwiftKey types (". ", a new field), so sentence starts come from the editor. A
     * capital Nintype adds only because of its sentence start is dropped when the trie has the
     * word in lower case; capitals the word carries itself ("I", names) stay.
     */
    static String caseWord(String w, String[] candidates, boolean start) {
        if (w.isEmpty()) return w;
        String lower = w.toLowerCase(Locale.ROOT);
        if (lower.equals("i") || lower.startsWith("i'") || lower.startsWith("i\u2019")) {
            return CapitalIPatch.isCapitalizeOn() || start ? "I" + w.substring(1) : lower;
        }
        if (start) return Character.toUpperCase(w.charAt(0)) + w.substring(1);
        String rest = w.substring(1);
        if (Character.isUpperCase(w.charAt(0)) && rest.equals(rest.toLowerCase(Locale.ROOT))) {
            boolean carriesCapital = false;
            for (String c : candidates) {
                if (c.equals(lower)) return lower;
                if (c.equalsIgnoreCase(w) && !c.equals(c.toLowerCase(Locale.ROOT))) carriesCapital = true;
            }
            if (!carriesCapital) return lower;
        }
        return w;
    }

    /** SwiftKey's own "Auto capitalize" setting (Typing settings), read live. */
    private static boolean autoCaps() {
        try {
            InputMethodService service = TwoThumbSwipe.service();
            if (service == null) return true;
            SharedPreferences prefs = service.getSharedPreferences(service.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            int id = service.getResources().getIdentifier("pref_auto_caps_default", "bool", service.getPackageName());
            boolean def = id == 0 || service.getResources().getBoolean(id);
            return prefs.getBoolean("pref_auto_caps", def);
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isSentenceStart(String before) {
        if (before == null) return false;
        String t = before.replaceAll("\\s+$", "");
        if (t.isEmpty()) return true;
        char last = t.charAt(t.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '\n';
    }

    /** Waits (bounded) until the decoder has handled every touch posted so far. */
    private static void drainDecoder() {
        Handler h = TwoThumbSwipe.handler();
        if (h == null) return;
        FutureTask<Void> barrier = new FutureTask<>(() -> null);
        h.post(barrier);
        try {
            barrier.get(COMMIT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (ModSettings.debug()) Log.w(TAG, "two-thumb: decoder still busy after " + COMMIT_WAIT_MS + " ms, committing the word it had");
        }
    }

    /** SwiftKey's key sound types (fb0.e.a, yg0.a.c): a character key, space, delete. */
    private static final int SOUND_KEY = 0;
    private static final int SOUND_SPACE = 32;
    private static final int SOUND_DELETE = -5;

    /** SwiftKey's own key-press feedback, as its key actions give it (ic0.g): the vibration (its
     * setting, strength and system-haptics choice) and the sound (its setting, volume and sound
     * profile) for [soundType]. */
    private static void keyFeedback(View view, int soundType) {
        try {
            if (view instanceof TwoThumbSwipe.KeyFeedback) ((TwoThumbSwipe.KeyFeedback) view).patch_twoThumbKeyFeedback(soundType);
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: key feedback failed", t);
        }
    }

    private static InputConnection editor() {
        InputMethodService service = TwoThumbSwipe.service();
        return service == null ? null : service.getCurrentInputConnection();
    }

    /** The cursor's absolute offset, or -1 when the editor has no extracted text. */
    private static int cursor(InputConnection ic) {
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et == null || et.selectionStart < 0) return -1;
        return et.startOffset + Math.min(et.selectionStart, et.selectionEnd);
    }

    /** Shows [word] as the open word's composing text, at its anchor (UI thread). */
    private static void showWord(String word) {
        InputConnection ic = editor();
        if (ic == null) return;
        if (!wordIntact(ic)) {
            abandonWord("editor changed under the word");
            return;
        }
        ic.beginBatchEdit();
        if (!anchored) {
            // A new word starts after whatever is there: close any region SwiftKey left open.
            ic.finishComposingText();
            anchor = cursor(ic);
            shown = 0;
            anchored = true;
        }
        if (anchor >= 0) ic.setComposingRegion(anchor, anchor + shown);
        ic.setComposingText(word, 1);
        ic.endBatchEdit();
        mirrorStale = true;
        shown = word.length();
        written = word;
    }

    /** Puts [text] in place of the open word and closes it ([text] may end with a separator). */
    private static void putWord(String text) {
        InputConnection ic = editor();
        if (ic != null && anchored && !wordIntact(ic)) {
            // the text under the word is no longer ours: write nothing over it
            if (ModSettings.debug()) Log.i(TAG, "two-thumb: editor changed under the word, \"" + text + "\" not written");
            ic = null;
        }
        if (ic != null) {
            ic.beginBatchEdit();
            if (!anchored) ic.finishComposingText();
            else if (anchor >= 0) ic.setComposingRegion(anchor, anchor + shown);
            ic.commitText(text, 1);
            ic.finishComposingText();
            ic.endBatchEdit();
            mirrorStale = true;
        }
        anchored = false;
        anchor = -1;
        shown = 0;
        written = "";
        logEditor();
    }

    /**
     * Whether the editor still shows the open word as the glue last wrote it, right before the
     * cursor. False after any edit the glue did not make: SwiftKey's suggestion bar replacing the
     * word, a cursor move, the app rewriting the field.
     */
    private static boolean wordIntact(InputConnection ic) {
        if (!anchored || written.isEmpty()) return true;
        try {
            CharSequence before = ic.getTextBeforeCursor(written.length(), 0);
            CharSequence sel = ic.getSelectedText(0);
            return before != null && written.contentEquals(before) && (sel == null || sel.length() == 0);
        } catch (Throwable t) {
            return true;
        }
    }

    /** At a press with a word open: close the word if the editor changed under it. */
    private static void checkWordIntact() {
        InputConnection ic = editor();
        if (ic != null && !wordIntact(ic)) abandonWord("editor changed under the word");
    }

    /** Closes the open word without writing anything (the editor's text is not ours any more) and
     * drops it from the engine; the next touch starts a fresh word. */
    private static void abandonWord(String why) {
        if (!wordOpen) return;
        String word = word();
        wordOpen = false;
        wordGen++;
        anchored = false;
        anchor = -1;
        shown = 0;
        written = "";
        closeBar();
        undo.clear();
        period.clear();
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: word \"" + word + "\" abandoned (" + why + ")");
        Handler h = TwoThumbSwipe.handler();
        if (h != null) h.post(() -> {
            if (session != null) session.dropWord();
        });
    }

    /**
     * Backspace on the open word: Nintype's own (the last touch group goes, the rest re-decodes).
     * When that empties the word, the word closes and the next backspace is SwiftKey's.
     */
    private static int backspaceWord(long uptime, int held) {
        Handler h = TwoThumbSwipe.handler();
        if (h == null) {
            clearWord("backspace");
            return -1;
        }
        final float hx = held >= 0 ? lastX[held] : 0, hy = held >= 0 ? lastY[held] : 0;
        final float hp = held >= 0 ? lastPressure[held] : 0, hs = held >= 0 ? lastSize[held] : 0;
        // 1: shortened, 0: emptied, 2: ignored (a pointer is down), -1: no word in the engine,
        // 3: [held] could not be taken out on its own (it stays in the word as a lift)
        FutureTask<Integer> task = new FutureTask<>(() -> {
            LiveSession s = session;
            int kept = barHistory.size();
            if (held < 0 && s != null) backspacedInWord = true;
            BackspaceResult r = s == null ? null
                    : held >= 0 ? s.removeTouch(held, hx, hy, hp, hs, uptime) : s.backspace(uptime);
            if (held >= 0 && r != null && !r.getHandled()) return 3;
            // the liquid callback inside the backspace may carry the removed part's ranking
            while (barHistory.size() > kept) barHistory.remove(barHistory.size() - 1);
            if (r == null) return -1;
            if (!r.getHandled()) return 2;
            if (r.getEmpty()) {
                display = "";
                return 0;
            }
            String lit = s.getLiteral();
            literalShown = lit == null ? null : caseWord(lit, words(r.getCandidates()), sentenceStart);
            BarList list = barAfterBackspace(r, lit);
            peekTiebreak(list);
            latest = list;
            return 1;
        });
        h.post(task);
        int res;
        try {
            res = task.get(BACKSPACE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (ModSettings.debug()) Log.w(TAG, "two-thumb: backspace did not finish in " + BACKSPACE_WAIT_MS + " ms, clearing the word", e);
            res = -1;
        }
        String what = held >= 0 ? "long-press took its touch out" : "backspace";
        switch (res) {
            case 1:
                if (ModSettings.debug()) Log.i(TAG, "two-thumb: " + what + ", word now \"" + word() + "\"");
                showWord(word());
                pushBar();
                break;
            case 0:
                if (anchored) putWord("");
                wordOpen = false;
                wordGen++;
                closeBar();
                if (ModSettings.debug()) Log.i(TAG, "two-thumb: " + what + ", the word is empty");
                break;
            case 2:
            case 3:
                break;
            default:
                clearWord(what);
        }
        return res;
    }

    /**
     * Backspace right after a two-thumb commit, with SwiftKey's "undo autocorrect on backspace" on:
     * when the editor still shows the commit right before the cursor, it becomes the literal the
     * user typed, without the space, cursor after it. False (nothing written) otherwise.
     */
    private static boolean revertCommit() {
        if (!undoAutocorrect()) {
            undo.clear();
            return false;
        }
        InputConnection ic = editor();
        if (ic == null) {
            undo.clear();
            return false;
        }
        String committed = undo.committed();
        String literal;
        try {
            CharSequence sel = ic.getSelectedText(0);
            literal = undo.revert(cursor(ic), ic.getTextBeforeCursor(undo.length(), 0), sel != null && sel.length() > 0);
        } catch (Throwable t) {
            undo.clear();
            return false;
        }
        if (literal == null) {
            if (ModSettings.debug()) Log.i(TAG, "two-thumb: backspace after \"" + committed + "\" not undone (editor changed)");
            return false;
        }
        ic.beginBatchEdit();
        ic.finishComposingText();
        ic.deleteSurroundingText(committed.length(), 0);
        ic.commitText(literal, 1);
        ic.endBatchEdit();
        mirrorStale = true;
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: backspace undid the correction \"" + committed.trim() + "\" -> \"" + literal + "\"");
        logEditor();
        return true;
    }

    /**
     * Space right after our own space committed a word, with SwiftKey's "double space for period"
     * on: when the editor still shows the word and its space right before the cursor, the space
     * becomes ". " (the next word's capital follows from the editor, as with autocap). False
     * (nothing written) otherwise; the commit is forgotten either way.
     */
    private static boolean doubleSpace(long time) {
        InputConnection ic = editor();
        if (ic == null) {
            period.clear();
            return false;
        }
        try {
            CharSequence sel = ic.getSelectedText(0);
            if (!period.take(quickPeriod(), time, cursor(ic), ic.getTextBeforeCursor(period.length(), 0),
                    sel != null && sel.length() > 0, undo)) return false;
        } catch (Throwable t) {
            period.clear();
            return false;
        }
        ic.beginBatchEdit();
        ic.finishComposingText();
        ic.deleteSurroundingText(1, 0);
        ic.commitText(TwoThumbDoubleSpace.PERIOD, 1);
        ic.endBatchEdit();
        mirrorStale = true;
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: double space, period added");
        logEditor();
        return true;
    }

    /** SwiftKey's own "double space for period" setting (pref_quick_period_key), read live. */
    private static boolean quickPeriod() {
        try {
            InputMethodService service = TwoThumbSwipe.service();
            if (service == null) return true;
            SharedPreferences prefs = service.getSharedPreferences(service.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            int id = service.getResources().getIdentifier("pref_quick_period_default", "bool", service.getPackageName());
            boolean def = id == 0 || service.getResources().getBoolean(id);
            return prefs.getBoolean("pref_quick_period_key", def);
        } catch (Throwable t) {
            return true;
        }
    }

    /** SwiftKey's own "undo autocorrect on backspace" setting, read live. */
    private static boolean undoAutocorrect() {
        try {
            InputMethodService service = TwoThumbSwipe.service();
            if (service == null) return true;
            SharedPreferences prefs = service.getSharedPreferences(service.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            int id = service.getResources().getIdentifier("pref_undo_autocorrect_on_backspace_default", "bool", service.getPackageName());
            boolean def = id == 0 || service.getResources().getBoolean(id);
            return prefs.getBoolean("pref_undo_autocorrect_on_backspace", def);
        } catch (Throwable t) {
            return true;
        }
    }

    /** One log line with the text before the cursor after a commit (read off the UI thread). */
    private static void logEditor() {
        Handler h = TwoThumbSwipe.handler();
        if (h != null) h.post(() -> {
            String before = TwoThumbSwipe.textBeforeCursor();
            if (before != null) {
                String tail = before.length() > 32 ? before.substring(before.length() - 32) : before;
                if (ModSettings.debug()) Log.i(TAG, "two-thumb: editor now \"" + tail.replace("\n", "\\n") + "|\"");
            }
        });
    }

    /** Commits the open word as it stands, followed by [separator] if given; then Nintype's own
     * commit (its learning) runs on the decoder thread without writing anything. */
    static void commitWord(String why, String separator) {
        if (!wordOpen) return;
        drainDecoder();
        awaitTiebreak();
        String word = word();
        // the apostrophe tiebreak's pick: Nintype learns it as its explicit choice, not its own top
        final String picked = swappedRaw();
        String text = word + (separator == null ? "" : separator);
        int start = anchored ? anchor : -1;
        boolean wasAnchored = anchored;
        if (!text.isEmpty() || anchored) putWord(text);
        // a word closed by our own space is an autocorrect SwiftKey's backspace convention can undo
        if (wasAnchored && separator != null) undo.record(start, text, literalShown);
        else undo.clear();
        // a word closed by our own space is one SwiftKey's double-space period can follow
        if (wasAnchored && " ".equals(separator)) period.record(start, text, lastDownTime);
        else period.clear();
        wordOpen = false;
        wordGen++;
        closeBar();
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: commit \"" + word + "\" (" + why + ")");
        VocabBridge.onCommitted(word);
        Handler h = TwoThumbSwipe.handler();
        if (h != null) h.post(() -> {
            try {
                if (session == null) return;
                if (picked == null) session.commit(false);
                else if (!session.commitChoice(picked)) session.dropWord();
            } catch (Throwable t) {
                Log.e(TAG, "two-thumb: Nintype commit failed", t);
                session = null;
            }
        });
    }

    /** Clears the open word from the editor and drops it from Nintype without learning it. */
    private static void clearWord(String why) {
        drainDecoder();
        String word = word();
        if (anchored) putWord("");
        wordOpen = false;
        wordGen++;
        closeBar();
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: cleared \"" + word + "\" (" + why + ")");
        Handler h = TwoThumbSwipe.handler();
        if (h != null) h.post(() -> {
            if (session != null) session.dropWord();
        });
    }

    /** Closes the open word as the editor shows it, without Nintype's learning (the user is about
     * to delete it), and drops it from the engine. */
    private static void closeWordAsTyped(String why) {
        if (!wordOpen) return;
        drainDecoder();
        String word = word();
        if (!word.isEmpty() || anchored) putWord(word);
        wordOpen = false;
        wordGen++;
        closeBar();
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: closed \"" + word + "\" as typed, not learned (" + why + ")");
        Handler h = TwoThumbSwipe.handler();
        if (h != null) h.post(() -> {
            if (session != null) session.dropWord();
        });
    }

    // =========================================================================================
    // Presses handed to SwiftKey: a letter's long-press, a backspace slide (UI thread)

    private static TwoThumbPress press;
    private static WeakReference<View> lastView = new WeakReference<>(null);
    /** Copy of the down of the letter armed for a long-press (handed to SwiftKey as is). */
    private static MotionEvent lpDown;
    /** The backspace pointer's samples since its down (time, x, y), replayed on a hand-off. */
    private static long[] bsT = new long[64];
    private static float[] bsX = new float[64], bsY = new float[64];
    private static int bsCount;
    /** The event the glue is dispatching to SwiftKey itself (the gate passes it through). */
    private static MotionEvent injecting;
    /** While a long-press down is handed over: how long the pointer has been held already. */
    private static long longPressHeld = -1;
    /** While a held backspace's down is handed over: how long it has been held already. */
    private static long repeatHeld = -1;
    /** Copy of the down of the backspace armed for a hold (handed to SwiftKey as is). */
    private static MotionEvent bsDown;
    private static final Runnable holdTimer = TwoThumbMode::onHoldDue;
    private static int longPressDefault = -1;

    private static final Runnable longPressTimer = TwoThumbMode::onLongPressDue;

    private static TwoThumbPress press(View view) {
        if (press == null) press = new TwoThumbPress(ViewConfiguration.get(view.getContext()).getScaledTouchSlop());
        return press;
    }

    /** Whether [event] is one the glue dispatches to SwiftKey itself (no routing). */
    static boolean isInjected(MotionEvent event) {
        return event != null && event == injecting;
    }

    private static void inject(View view, MotionEvent event) {
        injecting = event;
        try {
            view.onTouchEvent(event);
        } finally {
            injecting = null;
        }
    }

    private static SharedPreferences swiftKeyPrefs(InputMethodService service) {
        return service.getSharedPreferences(service.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }

    /** SwiftKey's long-press delay (Layout & keys > Long press delay): pref long_press_timeout,
     * default R.integer.prefs_longpress_duration_default (450 ms). */
    private static long longPressTimeout() {
        try {
            InputMethodService service = TwoThumbSwipe.service();
            if (service == null) return 450;
            if (longPressDefault < 0) {
                int id = service.getResources().getIdentifier("prefs_longpress_duration_default", "integer", service.getPackageName());
                longPressDefault = id == 0 ? 450 : service.getResources().getInteger(id);
            }
            return swiftKeyPrefs(service).getInt("long_press_timeout", longPressDefault);
        } catch (Throwable t) {
            return 450;
        }
    }

    /** SwiftKey's swipe-left-on-backspace (pref_quick_delete_key, default on). */
    private static boolean swipeDelete() {
        try {
            InputMethodService service = TwoThumbSwipe.service();
            if (service == null) return true;
            int id = service.getResources().getIdentifier("pref_quick_delete_default", "bool", service.getPackageName());
            boolean def = id == 0 || service.getResources().getBoolean(id);
            return swiftKeyPrefs(service).getBoolean("pref_quick_delete_key", def);
        } catch (Throwable t) {
            return true;
        }
    }

    private static void armLongPress(TwoThumbPress p, MotionEvent event, int index, int id, float x, float y) {
        long due = p.letterDown(id, event.getEventTime(), x, y, event.getPointerCount() - 1, longPressTimeout());
        ui.removeCallbacks(longPressTimer);
        if (lpDown != null) lpDown.recycle();
        lpDown = null;
        if (due < 0) return;
        // isolated: this is the gesture's ACTION_DOWN with this one pointer
        lpDown = MotionEvent.obtain(event);
        ui.postAtTime(longPressTimer, due);
    }

    /**
     * The long-press delay passed with the letter held still and alone: its touch leaves the word
     * (Nintype's backspace on its own touch group), an open word commits as for punctuation, and the
     * pointer's original down goes to SwiftKey, whose long-press timer then runs only for what is
     * left of its delay ({@link #longPressDelay}).
     */
    private static void onLongPressDue() {
        TwoThumbPress p = press;
        MotionEvent down = lpDown;
        if (p == null || down == null) return;
        int id = p.fireLongPress(SystemClock.uptimeMillis());
        if (id == TwoThumbPress.NONE || down.getPointerId(0) != id || owner[id] != ENGINE || !gestureActive || !wordOpen) return;
        View view = lastView.get();
        if (view == null) return;
        int res = backspaceWord(SystemClock.uptimeMillis(), id);
        if (res == 2 || res == 3) {
            owner[id] = SWALLOWED;
            TouchTrail.end(id);
            if (ModSettings.debug()) Log.i(TAG, "two-thumb: long-press on \"" + downLabel[id] + "\" not taken: its touch shares a group, kept as typed");
            return;
        }
        if (wordOpen) commitWord("before a long-press on \"" + downLabel[id] + "\"", null);
        resyncSwiftKey("long-press");
        owner[id] = SWIFTKEY;
        TouchTrail.end(id);
        lpDown = null;
        longPressHeld = SystemClock.uptimeMillis() - down.getEventTime();
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: long-press on \"" + downLabel[id] + "\" to SwiftKey after " + longPressHeld + " ms");
        try {
            inject(view, down);
        } finally {
            longPressHeld = -1;
        }
        // not recycled: SwiftKey's long-press keeps the event it was given
    }

    /**
     * Called by SwiftKey's long-press delegate (patch) with the delay it is about to post for a key
     * down. For the down the glue hands over, the pointer has been held that long already, so only
     * the rest is left (else SwiftKey would wait a second full delay).
     */
    public static long longPressDelay(long delay) {
        long held = longPressHeld;
        if (held < 0) return delay;
        longPressHeld = -1;
        long left = TwoThumbPress.handoverDelay(delay, held);
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: SwiftKey long-press delay " + delay + " ms -> " + left + " ms");
        return left;
    }

    /**
     * Called by SwiftKey's key-repeat delegate (patch) with the delay before the first repeat of a
     * key down. For the held backspace the glue hands over, only what is left of it (else the
     * repeat would start a full delay after the hand-over).
     */
    public static long repeatDelay(long delay) {
        long held = repeatHeld;
        if (held < 0) return delay;
        repeatHeld = -1;
        long left = TwoThumbPress.handoverDelay(delay, held);
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: SwiftKey repeat delay " + delay + " ms -> " + left + " ms");
        return left;
    }

    private static void armHold(MotionEvent event, long due) {
        ui.removeCallbacks(holdTimer);
        bsDown = null;
        if (due < 0) return;
        // alone: this is the gesture's ACTION_DOWN with this one pointer
        bsDown = MotionEvent.obtain(event);
        ui.postAtTime(holdTimer, due);
    }

    /**
     * The backspace was held still and alone for SwiftKey's long-press delay, which is when
     * SwiftKey's own held backspace deletes the last word. The open word closes as typed (not
     * learned, dropped from the engine, bar closed), SwiftKey is resynced, and the pointer's down
     * and moves so far go to SwiftKey with their original times. With the time already held taken
     * off its timers, SwiftKey's long-press fires at once and deletes that word, and its repeat
     * keeps deleting words on its own schedule until the lift.
     */
    private static void onHoldDue() {
        TwoThumbPress p = press;
        MotionEvent down = bsDown;
        if (p == null || down == null) return;
        int id = p.fireHold(SystemClock.uptimeMillis());
        if (id == TwoThumbPress.NONE || down.getPointerId(0) != id || owner[id] != BACKSPACE || !gestureActive) return;
        View view = lastView.get();
        if (view == null || !view.isShown()) return;
        bsDown = null;
        undo.clear();
        period.clear();
        closeWordAsTyped("backspace hold");
        resyncSwiftKey("backspace hold");
        owner[id] = SWIFTKEY;
        long held = SystemClock.uptimeMillis() - down.getEventTime();
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: backspace hold to SwiftKey after " + held + " ms");
        longPressHeld = held;
        repeatHeld = held;
        try {
            inject(view, down);
            replayBackspaceMoves(view, down, 0);
        } finally {
            longPressHeld = -1;
            repeatHeld = -1;
        }
        bsCount = 0;
        // not recycled: SwiftKey's long-press keeps the event it was given
    }

    /** Move events: the open-word backspace pointer's samples; a slide hands it to SwiftKey. */
    private static void backspaceMoves(View view, MotionEvent event) {
        TwoThumbPress p = press;
        if (p == null) return;
        int count = event.getPointerCount();
        int history = event.getHistorySize();
        for (int i = 0; i < count; i++) {
            int id = event.getPointerId(i);
            if (id >= MAX_POINTERS || owner[id] != BACKSPACE) continue;
            for (int h = 0; h < history; h++) p.moved(id, event.getHistoricalX(i, h), event.getHistoricalY(i, h));
            p.moved(id, event.getX(i), event.getY(i));
            if (p.backspaceSlides(id, count)) {
                handOffBackspace(view, event, i, id);
                continue;
            }
            for (int h = 0; h < history; h++) recordBackspace(event.getHistoricalEventTime(h), event.getHistoricalX(i, h), event.getHistoricalY(i, h));
            recordBackspace(event.getEventTime(), event.getX(i), event.getY(i));
        }
    }

    private static void recordBackspace(long t, float x, float y) {
        if (bsCount == bsT.length) {
            bsT = Arrays.copyOf(bsT, bsCount * 2);
            bsX = Arrays.copyOf(bsX, bsCount * 2);
            bsY = Arrays.copyOf(bsY, bsCount * 2);
        }
        bsT[bsCount] = t;
        bsX[bsCount] = x;
        bsY[bsCount] = y;
        bsCount++;
    }

    /**
     * A backspace on an open word turned into a slide: the word stays as typed (not learned),
     * SwiftKey's mirror is resynced, and SwiftKey gets the pointer's down and moves so far with
     * their original times (its swipe detector reads their velocity), then the rest live: its
     * swipe-to-delete runs on the real text.
     */
    private static void handOffBackspace(View view, MotionEvent event, int index, int id) {
        press.backspaceHandedOff(id);
        closeWordAsTyped("backspace slide");
        resyncSwiftKey("backspace slide");
        owner[id] = SWIFTKEY;
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        event.getPointerProperties(index, props[0]);
        MotionEvent.PointerCoords[] c = {new MotionEvent.PointerCoords()};
        event.getPointerCoords(index, c[0]);
        long t0 = downTime[id];
        c[0].x = downX[id];
        c[0].y = downY[id];
        MotionEvent down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, 1, props, c, event.getMetaState(),
                event.getButtonState(), event.getXPrecision(), event.getYPrecision(), event.getDeviceId(),
                event.getEdgeFlags(), event.getSource(), event.getFlags());
        inject(view, down);
        replayBackspaceMoves(view, event, index);
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: backspace slide to SwiftKey (down + " + bsCount + " samples replayed)");
        bsCount = 0;
    }

    /** The backspace pointer's moves since its down, as one batched MOVE with their original times
     * (pointer [index] of [event] gives the pointer's properties). */
    private static void replayBackspaceMoves(View view, MotionEvent event, int index) {
        if (bsCount == 0) return;
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        event.getPointerProperties(index, props[0]);
        MotionEvent.PointerCoords[] c = {new MotionEvent.PointerCoords()};
        event.getPointerCoords(index, c[0]);
        long t0 = downTime[props[0].id];
        c[0].x = bsX[0];
        c[0].y = bsY[0];
        MotionEvent moves = MotionEvent.obtain(t0, bsT[0], MotionEvent.ACTION_MOVE, 1, props, c, event.getMetaState(),
                event.getButtonState(), event.getXPrecision(), event.getYPrecision(), event.getDeviceId(),
                event.getEdgeFlags(), event.getSource(), event.getFlags());
        for (int k = 1; k < bsCount; k++) {
            c[0].x = bsX[k];
            c[0].y = bsY[k];
            moves.addBatch(bsT[k], c, event.getMetaState());
        }
        inject(view, moves);
    }

    // =========================================================================================
    // Suggestion bar: Nintype's alternatives for the open word, on SwiftKey's own bar

    /** Implemented by SwiftKey's input model (added by the patch): asks for a candidates update
     * (type DEFAULT, cache cleared), the same call SwiftKey makes itself. */
    public interface InputModel {
        void patch_refreshTwoThumbBar();

        /** SwiftKey's selection-update handler with no positions: re-reads the editor into its
         * mirror (ec0.x) and re-applies its composing region. */
        void patch_resyncTwoThumbEditor();
    }

    /** True from a glue write to the editor until SwiftKey's mirror is next brought up to date. */
    private static boolean mirrorStale;

    /**
     * UI thread, before SwiftKey handles a key: if the glue wrote to the editor since the last
     * resync, SwiftKey re-reads the editor now. Its own selection updates for those writes may
     * still be queued, and its separator path (li0.m.s, ac0.f) commits the word its mirror holds
     * with setComposingText, which with a stale mirror inserts an old copy of our word.
     */
    private static void resyncSwiftKey(String label) {
        if (!mirrorStale) return;
        mirrorStale = false;
        Object model = inputModel.get();
        if (!(model instanceof InputModel)) return;
        try {
            ((InputModel) model).patch_resyncTwoThumbEditor();
            if (ModSettings.debug()) Log.i(TAG, "two-thumb: SwiftKey resynced before \"" + label + "\"");
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: SwiftKey resync failed", t);
        }
    }

    /** At most this many alternatives go to the bar (SwiftKey shows three, more when expanded). */
    private static final int BAR_SIZE = 5;
    /** After a word closes the bar refreshes once more, a little later, so SwiftKey's editor mirror
     * has seen the commit and its own candidates fit the new text. */
    private static final long CLOSE_REFRESH_DELAY_MS = 60;

    /** A bar list: what the bar shows (cased like the composing text) and the engine's spelling. */
    private static final class BarList {
        final String[] shown, raw;
        /** The literal (raw, null: none) and whether the user forced it as the word (a backspace). */
        String literal;
        boolean forced;
        /**
         * The apostrophe tiebreak's result for this list: null = not decided yet, this = Nintype's
         * order stands, another list = the other variant on top. Drives both the composing text
         * ({@link #word()}) and the bar.
         */
        volatile BarList resolved;

        BarList(String[] shown, String[] raw) {
            this.shown = shown;
            this.raw = raw;
        }

        boolean sameAs(BarList o) {
            return o != null && Arrays.equals(shown, o.shown) && Arrays.equals(raw, o.raw);
        }
    }

    /** The bar for one word generation; its Predictions are built once, on Fluency's thread. */
    private static final class Bar {
        final int gen;
        final BarList list;
        Object predictions;
        /** The list the Predictions were built from (the tiebreak's order); guarded by this. */
        BarList used;
        final Map<Object, Integer> index = new IdentityHashMap<>();

        Bar(int gen, BarList list) {
            this.gen = gen;
            this.list = list;
        }
    }

    private static WeakReference<Object> inputModel = new WeakReference<>(null);
    /** Latest alternatives from the decoder thread (liquid updates, backspace). */
    private static volatile BarList latest;
    /** What the bar shows now (null: SwiftKey's own candidates). Set on the UI thread. */
    private static volatile Bar bar;
    /** Every Prediction the bar got from us (identity), for the tap and learning checks. */
    private static final Map<Object, Long> barOurs = Collections.synchronizedMap(new IdentityHashMap<>());
    private static Constructor<?> predictionCtor, predictionsCtor;
    private static final Map<Class<?>, Object> predictionFields = new ConcurrentHashMap<>();
    private static final Object NO_FIELD = new Object();

    /** Called from SwiftKey's input model constructor (patch). */
    public static void setInputModel(Object model) {
        inputModel = new WeakReference<>(model);
    }

    /** The bar list for [text] (Nintype's current word), its [literal] (the keys typed, null: none)
     * and its ranked [candidates]: the word first, the literal second (SwiftKey's left slot), then
     * the rest in Nintype's order ({@link TwoThumbBarOrder}), each cased like the composing text. */
    static BarList barList(String text, String literal, String[] candidates, boolean start) {
        List<String> raw = TwoThumbBarOrder.order(text, literal, candidates, BAR_SIZE);
        String[] r = raw.toArray(new String[0]);
        String[] shown = new String[r.length];
        for (int i = 0; i < r.length; i++) shown[i] = caseWord(r[i], candidates, start);
        // casing may fold two spellings into one ("Us" / "us" at a sentence start)
        List<String> s2 = new ArrayList<>(), r2 = new ArrayList<>();
        for (int i = 0; i < r.length; i++) {
            if (s2.contains(shown[i])) continue;
            s2.add(shown[i]);
            r2.add(r[i]);
        }
        BarList list = new BarList(s2.toArray(new String[0]), r2.toArray(new String[0]));
        list.literal = literal;
        list.forced = isForced(text, literal);
        return list;
    }

    /** Decoder thread: the user forced the literal (Nintype makes it the word after a backspace). */
    private static boolean isForced(String text, String literal) {
        return backspacedInWord && literal != null && text != null && ApostropheTiebreak.norm(literal).equals(ApostropheTiebreak.norm(text));
    }

    // =========================================================================================
    // Apostrophe tiebreak ("its" / "it's"), scored by SwiftKey's language model on Fluency's thread

    private static final ApostropheTiebreak tiebreak = new ApostropheTiebreak(VocabBridge::score);
    /** The text before the open word (decoder thread at word start; null: none, e.g. a password field). */
    private static volatile String wordBefore;
    /** A user backspace happened in the open word (decoder thread). */
    private static boolean backspacedInWord;

    /**
     * The open word as the editor shows it: Nintype's top, or the tiebreak's pick when the latest
     * alternatives were resolved to another variant of the same word.
     */
    private static String word() {
        String d = display;
        BarList l = latest;
        BarList r = resolvedFor(l);
        if (r != null && r != l && r.shown.length > 0 && l.shown.length > 0 && d.equalsIgnoreCase(l.shown[0])) return r.shown[0];
        return d;
    }

    /** The raw spelling of the tiebreak's pick for the open word, or null when Nintype's top stands. */
    private static String swappedRaw() {
        BarList l = latest;
        BarList r = resolvedFor(l);
        if (r != null && r != l && r.raw.length > 0 && l.shown.length > 0 && display.equalsIgnoreCase(l.shown[0])) return r.raw[0];
        return null;
    }

    /**
     * [l]'s decision; for a list equal to the one the bar shows (pushBar keeps the older object
     * then), the bar list's decision.
     */
    private static BarList resolvedFor(BarList l) {
        if (l == null) return null;
        BarList r = l.resolved;
        if (r == null) {
            Bar b = bar;
            if (b != null && b.list != l && b.list.sameAs(l)) r = b.list.resolved;
        }
        return r;
    }

    /** Decoder thread: applies a cached decision to [list] right away (no flicker, no query). */
    private static void peekTiebreak(BarList list) {
        if (list.resolved != null) return;
        ApostropheTiebreak.Question q = tiebreakQuestion(list);
        if (q == null) return;
        ApostropheTiebreak.Decision d = tiebreak.peek(wordBefore, q);
        if (d != null) resolveWith(list, d);
    }

    private static ApostropheTiebreak.Question tiebreakQuestion(BarList list) {
        if (wordBefore == null) return null;
        return ApostropheTiebreak.question(list.raw, list.literal, list.forced);
    }

    /**
     * Fluency's thread (inside the candidate update, via barPredictions): decides [list] if no
     * decision is attached yet, querying Fluency on a cache miss. Returns the list to show.
     */
    private static BarList resolveTiebreak(BarList list) {
        BarList r = list.resolved;
        if (r != null) return r;
        ApostropheTiebreak.Question q = tiebreakQuestion(list);
        String context = wordBefore;
        if (q == null || context == null || !VocabBridge.canScore()) return list;
        try {
            long t0 = System.nanoTime();
            int before = tiebreak.queries;
            ApostropheTiebreak.Decision d = tiebreak.decide(context, q);
            if (tiebreak.queries != before) {
                if (ModSettings.debug()) Log.i(TAG, "two-thumb: Fluency scored \"" + q.top + "\" / \"" + q.other + "\" in "
                        + (System.nanoTime() - t0) / 1000 + " us");
            }
            return resolveWith(list, d);
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: apostrophe tiebreak failed, Nintype's order kept", t);
            markResolved(list, list);
            return list;
        }
    }

    /** How long a commit waits for Fluency to decide the word's latest alternatives. */
    private static final long TIEBREAK_WAIT_MS = 40;
    private static final Object tiebreakLock = new Object();

    /**
     * UI thread, at a commit right after the last lift: the decision for the latest list may still
     * be on its way (its bar refresh is queued behind this very commit), so ask now and wait a
     * little. Fluency's thread never waits on the UI thread, so the wait is only ever bounded.
     */
    private static void awaitTiebreak() {
        BarList l = latest;
        if (l == null || resolvedFor(l) != null || tiebreakQuestion(l) == null || !VocabBridge.canScore()) return;
        pushBar();
        long end = SystemClock.uptimeMillis() + TIEBREAK_WAIT_MS;
        synchronized (tiebreakLock) {
            while (resolvedFor(l) == null) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) {
                    if (ModSettings.debug()) Log.i(TAG, "two-thumb: apostrophe tiebreak not decided " + TIEBREAK_WAIT_MS + " ms into the commit, Nintype's word kept");
                    return;
                }
                try {
                    tiebreakLock.wait(left);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void markResolved(BarList list, BarList out) {
        synchronized (tiebreakLock) {
            list.resolved = out;
            tiebreakLock.notifyAll();
        }
    }

    private static BarList resolveWith(BarList list, ApostropheTiebreak.Decision d) {
        ApostropheTiebreak.Question q = d.question;
        BarList out = list;
        if (d.swap) {
            String addedShown = q.added() ? caseWord(q.other, new String[0], sentenceStart) : null;
            String[] shown = ApostropheTiebreak.reorder(Arrays.asList(list.shown), q, addedShown, BAR_SIZE).toArray(new String[0]);
            String[] raw = ApostropheTiebreak.reorder(Arrays.asList(list.raw), q, q.other, BAR_SIZE).toArray(new String[0]);
            out = new BarList(shown, raw);
            out.literal = list.literal;
            out.forced = list.forced;
            out.resolved = out;
        }
        markResolved(list, out);
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: apostrophe tiebreak \"" + q.top + "\" " + fmt(d.topScore) + " vs \"" + q.other + "\" "
                + fmt(d.otherScore) + (q.added() ? " (contraction not in Nintype's list)" : "") + ": " + d.why
                + (d.swap ? ", \"" + out.shown[0] + "\" on top" : ", Nintype's order kept") + (d.cached ? " (cached)" : ""));
        return out;
    }

    private static String fmt(double p) {
        return p < 0 ? "absent" : String.format(Locale.ROOT, "%.3g", p);
    }

    /** UI thread: the bar's list was resolved to another variant on Fluency's thread; show it. */
    private static void applySwap(Bar b) {
        if (bar != b || !wordOpen || b.gen != wordGen) return;
        String w = word();
        if (w.equals(written)) return;
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: word \"" + display + "\" shown as \"" + w + "\" (apostrophe tiebreak)");
        showWord(w);
    }

    private static String[] words(List<Candidate> candidates) {
        String[] out = new String[candidates.size()];
        for (int i = 0; i < out.length; i++) out[i] = candidates.get(i).getWord();
        return out;
    }

    /** The open word's bar lists, oldest first (decoder thread). */
    private static final List<BarList> barHistory = new ArrayList<>();

    /**
     * Decoder thread: the bar after a backspace. The re-decode's own ranking when it made one;
     * otherwise (a word of taps, or the rest of the word is an earlier merge buffer's result) the
     * list the word had when it last read the same, else the word alone.
     */
    private static BarList barAfterBackspace(BackspaceResult r, String literal) {
        String text = r.getText();
        if (!r.getCandidates().isEmpty()) {
            BarList list = barList(text, literal, words(r.getCandidates()), sentenceStart);
            barHistory.add(list);
            return list;
        }
        boolean forcedNow = isForced(text, literal);
        for (int i = barHistory.size() - 1; i >= 0; i--) {
            BarList old = barHistory.get(i);
            if (old.raw.length > 0 && old.raw[0].equalsIgnoreCase(text) && old.forced == forcedNow) {
                while (barHistory.size() > i + 1) barHistory.remove(barHistory.size() - 1);
                return old;
            }
        }
        BarList list = barList(text, literal, new String[0], sentenceStart);
        barHistory.add(list);
        return list;
    }

    /** UI thread: shows the decoder's latest alternatives on the bar if they changed. */
    private static void pushBar() {
        BarList list = latest;
        if (!wordOpen || list == null || list.shown.length == 0) return;
        Bar current = bar;
        if (current != null && current.gen == wordGen && current.list.sameAs(list)) return;
        bar = new Bar(wordGen, list);
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: bar " + Arrays.toString(list.shown));
        refreshBar();
    }

    /** UI thread: the word closed, the bar goes back to SwiftKey's own candidates. */
    private static void closeBar() {
        latest = null;
        if (bar == null) return;
        bar = null;
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: bar back to SwiftKey's candidates");
        ui.postDelayed(TwoThumbMode::refreshBar, CLOSE_REFRESH_DELAY_MS);
    }

    private static void refreshBar() {
        Object model = inputModel.get();
        if (!(model instanceof InputModel)) return;
        try {
            ((InputModel) model).patch_refreshTwoThumbBar();
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: bar refresh failed", t);
        }
    }

    /**
     * Fluency's thread, from the candidate update callable (via TwoThumbSwipe.substitute): our
     * alternatives as SwiftKey's candidate result for every request while a word is open.
     *
     * @return Our Predictions, or null for SwiftKey's own.
     */
    static Object barPredictions() {
        Bar b = bar;
        if (b == null) return null;
        try {
            synchronized (b) {
                if (b.predictions == null) {
                    BarList use = resolveTiebreak(b.list);
                    b.used = use;
                    if (use != b.list) ui.post(() -> applySwap(b));
                    if (predictionCtor == null) {
                        predictionCtor = Class.forName("com.microsoft.fluency.Prediction").getConstructor(String.class, double.class);
                        predictionsCtor = Class.forName("com.microsoft.fluency.Predictions").getConstructor(List.class);
                    }
                    List<Object> out = new ArrayList<>();
                    double probability = 0.8;
                    long now = SystemClock.uptimeMillis();
                    for (int i = 0; i < use.shown.length; i++) {
                        Object prediction = predictionCtor.newInstance(use.shown[i], probability);
                        b.index.put(prediction, i);
                        barOurs.put(prediction, now);
                        out.add(prediction);
                        probability /= 2;
                    }
                    synchronized (barOurs) {
                        barOurs.values().removeIf(t -> t < now - 120_000);
                    }
                    b.predictions = predictionsCtor.newInstance(out);
                }
                return b.predictions;
            }
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: building the bar failed, SwiftKey's own candidates kept", t);
            return null;
        }
    }

    /** Whether [prediction] is one the bar got from us (learning guard). */
    static boolean isBarPrediction(Object prediction) {
        return prediction != null && barOurs.containsKey(prediction);
    }

    /**
     * The Fluency Prediction a SwiftKey candidate carries, or null: FluencyCandidate's own field, or
     * through the wrappers the bar's list transforms put around a candidate (a field of a candidate
     * interface the wrapper implements itself).
     */
    private static Object predictionOf(Object candidate) {
        for (int depth = 0; candidate != null && depth < 4; depth++) {
            Class<?> cls = candidate.getClass();
            Object f = predictionFields.get(cls);
            if (f == null) {
                f = NO_FIELD;
                Field inner = null;
                search:
                for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field field : c.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                        Class<?> type = field.getType();
                        if (type.getName().equals("com.microsoft.fluency.Prediction")) {
                            field.setAccessible(true);
                            f = field;
                            break search;
                        }
                        if (inner == null && type.isInterface() && type.isInstance(candidate)) inner = field;
                    }
                }
                if (f == NO_FIELD && inner != null) {
                    inner.setAccessible(true);
                    f = new Field[]{inner};
                }
                predictionFields.put(cls, f);
            }
            try {
                if (f instanceof Field) return ((Field) f).get(candidate);
                if (f instanceof Field[]) {
                    candidate = ((Field[]) f)[0].get(candidate);
                    continue;
                }
            } catch (Throwable t) {
                return null;
            }
            return null;
        }
        return null;
    }

    /**
     * UI thread, at the start of SwiftKey's onPredictionSelected (patch).
     *
     * @return True when the candidate is ours: the tap is handled here and SwiftKey's own commit
     * (its replace of the current word) must not run.
     */
    public static boolean onCandidateSelected(Object candidate) {
        try {
            Object prediction = predictionOf(candidate);
            if (prediction == null || !barOurs.containsKey(prediction)) return false;
            Bar b = bar;
            Integer i = null;
            BarList used = null;
            if (b != null) {
                synchronized (b) {
                    i = b.index.get(prediction);
                    used = b.used;
                }
            }
            if (i == null || used == null || b.gen != wordGen || !wordOpen) {
                if (ModSettings.debug()) Log.i(TAG, "two-thumb: bar tap on an old alternative, ignored");
                refreshBar();
                return true;
            }
            pickAlternative(used.shown[i], used.raw[i], i);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: bar tap failed", t);
            return false;
        }
    }

    /** Replaces the open word with alternative [shown] and commits it with a space, as the space key
     * does; Nintype learns the chosen word. */
    private static void pickAlternative(String shown, String raw, int rank) {
        InputConnection ic = editor();
        if (ic != null && !wordIntact(ic)) {
            if (ModSettings.debug()) Log.i(TAG, "two-thumb: bar tap #" + rank + " \"" + shown + "\" not written (editor changed under the word)");
            abandonWord("editor changed under the word");
            return;
        }
        drainDecoder();
        String before = word();
        undo.clear();
        period.clear();
        putWord(shown + " ");
        wordOpen = false;
        wordGen++;
        closeBar();
        if (ModSettings.debug()) Log.i(TAG, "two-thumb: bar tap #" + rank + " \"" + shown + "\" replaces \"" + before + "\"");
        VocabBridge.onCommitted(shown);
        Handler h = TwoThumbSwipe.handler();
        if (h != null) h.post(() -> {
            try {
                LiveSession s = session;
                if (s == null) return;
                String top = liquidRaw;
                if (top != null && top.equalsIgnoreCase(raw)) {
                    // Nintype's own pick: its commit (forceMergeAllBuffers) learns it
                    s.commit(false);
                } else if (s.commitChoice(raw)) {
                    // Another alternative of Nintype's final list (the literal included): its
                    // explicit-choice commit (the forced WordAlternative) learns the chosen word.
                    if (ModSettings.debug()) Log.i(TAG, "two-thumb: Nintype committed bar pick \"" + raw + "\" as its explicit choice");
                } else {
                    // Not in the final list (a search candidate only): Nintype's own commit would
                    // learn its top word, so the engine's word goes unlearned. learnWords is no
                    // substitute: it skips words the trie already has (gradlew barCheck).
                    s.dropWord();
                    if (ModSettings.debug()) Log.i(TAG, "two-thumb: Nintype's word dropped for bar pick \"" + raw + "\" (not learned by Nintype)");
                }
            } catch (Throwable t) {
                Log.e(TAG, "two-thumb: Nintype commit failed", t);
                session = null;
            }
        });
    }

    // =========================================================================================
    // Decoder thread

    private static void engineTouches(TouchLog batch, int gen) {
        LiveSession s = session;
        if (s == null) return;
        try {
            for (int i = 0; i < batch.size; i++) {
                s.touch(batch.pid[i], batch.action[i], batch.x[i], batch.y[i], batch.pressure[i], batch.touchSize[i],
                        batch.time[i]);
            }
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: engine touch failed, word dropped", t);
            s.dropWord();
        }
        startTicking();
    }

    /** Real-time frames while a pointer is down, so Nintype sees a held touch age. */
    private static void startTicking() {
        if (ticking) return;
        Handler h = TwoThumbSwipe.handler();
        if (h == null) return;
        ticking = true;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                LiveSession s = session;
                try {
                    if (s != null) s.advance(SystemClock.uptimeMillis());
                } catch (Throwable t) {
                    Log.e(TAG, "two-thumb: engine frame failed", t);
                }
                if (s != null && s.getLiveTouches() > 0) h.postDelayed(this, 16);
                else ticking = false;
            }
        }, 16);
    }

    private static final LiveSession.Listener listener = new LiveSession.Listener() {
        @Override
        public void liquid(String text, String[] candidates, String literal) {
            if (text.isEmpty()) return;
            String word = caseWord(text, candidates, sentenceStart);
            display = word;
            liquidRaw = text;
            literalShown = literal == null ? null : caseWord(literal, candidates, sentenceStart);
            BarList list = barList(text, literal, candidates, sentenceStart);
            peekTiebreak(list);
            latest = list;
            barHistory.add(list);
            final int gen = wordGen;
            if (ModSettings.debug()) Log.i(TAG, "two-thumb: word \"" + word + "\" " + Arrays.toString(Arrays.copyOf(candidates,
                    Math.min(4, candidates.length))) + (literal == null ? "" : " literal \"" + literal + "\""));
            ui.post(() -> {
                if (gen != wordGen || !wordOpen) return;
                showWord(word());
                pushBar();
            });
        }

        @Override
        public void solidify(String text, boolean explicit) {
            // Only Nintype's own commits get here (ours are silent): put the text in and keep going.
            String word = text.trim();
            if (word.isEmpty()) return;
            String cased = caseWord(word, new String[0], sentenceStart);
            final String out = text.replace(word, cased);
            final int gen = wordGen;
            sentenceStart = false;
            if (ModSettings.debug()) Log.i(TAG, "two-thumb: Nintype committed \"" + out + "\" itself");
            ui.post(() -> {
                if (gen != wordGen) return;
                putWord(out);
            });
        }
    };
}
