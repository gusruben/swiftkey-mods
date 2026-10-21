package app.revanced.extension.swiftkey.twothumb;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.RectF;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.swiftkey.settings.ModSettings;
import app.revanced.extension.swiftkey.twothumb.nintype.NintypeDecoder;
import kotlin.Unit;
import nintype.engine.geometry.KeyboardModel;
import nintype.engine.trie.LearnedStore;

/**
 * Two-finger swipe mode's shared plumbing around {@link TwoThumbMode}: the Nintype engine (the
 * Kotlin port, {@link NintypeDecoder}) loaded on its own thread, the layout geometry it decodes on,
 * and the hooks the patch injects into SwiftKey.
 * <ul>
 *   <li><b>Gate</b> ({@link #filterTouch}, main keyboard view's dispatch, UI thread): hands every
 *   event to {@link TwoThumbMode} while the mode is on, else passes it through unchanged.</li>
 *   <li><b>Substitute</b> ({@link #substitute}, Fluency's thread): while a word is open, the bar
 *   shows its alternatives instead of SwiftKey's predictions.</li>
 *   <li><b>Learning guard</b> ({@link #skipLearning}): the bar's own predictions are kept out of
 *   SwiftKey's key-press model.</li>
 * </ul>
 * Design: research/swiftkey-two-thumb-integration.md. Logs (with Mods "Debug logging" on) under
 * {@code adb logcat -s SwiftKeyMods}.
 */
@SuppressWarnings("unused")
public final class TwoThumbSwipe {
    private static final String TAG = "SwiftKeyMods";

    /** Added to the base keyboard view by the patch: SwiftKey's own key-press feedback
     * (vibration and sound, fb0.e.a) for a sound type (0 key, 32 space, -5 delete). */
    public interface KeyFeedback {
        void patch_twoThumbKeyFeedback(int soundType);
    }

    /** Added to the keyboard view class (and so every key layout view) by the patch. */
    public interface KeyboardView {
        List<?> patch_getTwoThumbKeys();

        /** The key's area, normalised to the keyboard view (0 to 1), or null for a non-key. */
        RectF patch_getTwoThumbKeyArea(Object key);

        /** The key's content description: the letter for letter keys, a name for others. */
        CharSequence patch_getTwoThumbKeyLabel(Object key);
    }

    private static final String DATA_DIR = "nintype";
    // Same order as the patch's file list: NintypeData.file(i) is DATA_FILES[i].
    private static final String[] DATA_FILES = {
            "e2e/BOOT.json", "e2e/moopad_boot.bin", "e2e_search_boot.json", "trie/english/english.nbipack",
    };

    // ---- decoder thread state ----
    private static Handler handler;
    private static volatile NintypeDecoder decoder;
    private static volatile KeyboardModel model;
    private static volatile String textBefore;
    private static WeakReference<InputMethodService> serviceRef = new WeakReference<>(null);

    // ---- UI thread state ----
    private static int layoutSignature;
    // The current layout as the UI thread reads it (two-finger swipe mode routes touches by it).
    private static Object[] uiKeys = new Object[0];
    private static String[] uiLabels = new String[0];
    private static float[] uiRects = new float[0];
    private static int uiCount;

    private TwoThumbSwipe() {
    }

    // =========================================================================================
    // Startup

    /** Injected at the start of KeyboardService.onCreate. Loads the engine in the background. */
    public static synchronized void init(Context context) {
        try {
            if (context instanceof InputMethodService) serviceRef = new WeakReference<>((InputMethodService) context);
            ModSettings.preferences(context);
            // The engine's learned-state messages can name words: only with debug logging on.
            LearnedStore.defaultLog = message -> {
                if (ModSettings.debug()) Log.w(TAG, "nintype learned: " + message);
                return Unit.INSTANCE;
            };
            if (handler != null) return;
            HandlerThread thread = new HandlerThread("TwoFingerSwipe", Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            handler = new Handler(thread.getLooper());
            final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            handler.post(() -> load(app));
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: init failed", t);
        }
    }

    private static void load(Context context) {
        try {
            long t0 = SystemClock.uptimeMillis();
            File root = extractAssets(context);
            long t1 = SystemClock.uptimeMillis();
            File learned = VocabBridge.learnedDir(context);
            NintypeDecoder d = NintypeDecoder.load(root, learned);
            if (ModSettings.debug()) Log.i(TAG, "two-thumb: learned state " + (learned == null ? "not available (before first unlock), not persisted this run"
                    : "loaded from " + learned));
            long t2 = SystemClock.uptimeMillis();
            Runtime rt = Runtime.getRuntime();
            if (ModSettings.debug()) Log.i(TAG, "two-thumb: engine loaded (data " + (t1 - t0) + " ms, load " + (t2 - t1) + " ms, heap "
                    + (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024) + " MB)");
            decoder = d;
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: engine load failed, two-thumb swipe stays off", t);
        }
    }

    /** Writes the engine data out of the dex once per APK version (the engine reads files). */
    private static File extractAssets(Context context) throws Exception {
        Context storage = context.createDeviceProtectedStorageContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        File base = new File(storage.getNoBackupFilesDir(), DATA_DIR);
        File root = new File(base, Long.toString(info.lastUpdateTime));
        File done = new File(root, ".done");
        if (done.isFile()) return root;
        deleteRecursively(base);
        for (int i = 0; i < DATA_FILES.length; i++) {
            byte[] data = NintypeData.file(i);
            if (data == null) throw new IllegalStateException("Missing engine data " + DATA_FILES[i]);
            File out = new File(root, DATA_FILES[i]);
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
            try (OutputStream os = new FileOutputStream(out)) {
                os.write(data);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        done.createNewFile();
        return root;
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static KeyboardModel model() {
        return model;
    }

    static NintypeDecoder decoder() {
        return decoder;
    }

    static Handler handler() {
        return handler;
    }

    static InputMethodService service() {
        return serviceRef.get();
    }

    static void rebuildLayout(View view) {
        maybeRebuildLayout(view);
    }

    /** Index of the layout key under (x, y) in view px, or -1. */
    static int keyAt(float x, float y) {
        float[] r = uiRects;
        for (int i = 0; i < uiCount; i++) {
            if (x >= r[4 * i] && x < r[4 * i + 2] && y >= r[4 * i + 1] && y < r[4 * i + 3]) return i;
        }
        return -1;
    }

    static String keyLabel(int index) {
        return index >= 0 && index < uiCount ? uiLabels[index] : null;
    }

    static Object keyObject(int index) {
        return index >= 0 && index < uiCount ? uiKeys[index] : null;
    }

    /** Editor text before the cursor (binder call: not on the UI thread), null in password fields. */
    static String textBeforeCursor() {
        readTextBefore();
        return textBefore;
    }

    /** Whether the current field allows learning (not a password, no IME_FLAG_NO_PERSONALIZED_LEARNING). */
    static boolean learningAllowed() {
        InputMethodService service = serviceRef.get();
        if (service == null || privateField(service)) return false;
        EditorInfo info = service.getCurrentInputEditorInfo();
        return info != null && (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
    }

    /** Injected at the start of KeyboardService.onDestroy. */
    public static void onDestroy() {
        try {
            TwoThumbMode.onKeyboardHidden();
            VocabBridge.saveNow();
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: destroy handling failed", t);
        }
    }

    /** Injected at the start of KeyboardService.onFinishInputView. */
    public static void onKeyboardHidden() {
        try {
            VocabBridge.saveNow();
            TwoThumbMode.onKeyboardHidden();
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: keyboard hidden handling failed", t);
        }
    }

    // =========================================================================================
    // Geometry

    /** At each first down: rebuild the decoder's board when the keys' labels or rects changed. */
    private static void maybeRebuildLayout(View view) {
        if (!(view instanceof KeyboardView) || decoder == null) return;
        KeyboardView keyboard = (KeyboardView) view;
        List<?> keys = keyboard.patch_getTwoThumbKeys();
        if (keys == null) return;
        int width = view.getWidth(), height = view.getHeight();
        final int count = keys.size();
        final String[] labels = new String[count];
        final float[] rects = new float[4 * count];
        // Identity changes with every shift state, so hash what the board is built from instead.
        int signature = 31 * width + height;
        int n = 0;
        final Object[] keyObjects = new Object[count];
        for (Object key : keys) {
            RectF area = keyboard.patch_getTwoThumbKeyArea(key);
            if (area == null) continue;
            keyObjects[n] = key;
            CharSequence label = keyboard.patch_getTwoThumbKeyLabel(key);
            labels[n] = label == null ? null : label.toString().toLowerCase(Locale.ROOT);
            rects[4 * n] = area.left * width;
            rects[4 * n + 1] = area.top * height;
            rects[4 * n + 2] = area.right * width;
            rects[4 * n + 3] = area.bottom * height;
            signature = 31 * signature + (labels[n] == null ? 0 : labels[n].hashCode());
            for (int k = 0; k < 4; k++) signature = 31 * signature + Math.round(rects[4 * n + k]);
            n++;
        }
        if (signature == layoutSignature) return;
        layoutSignature = signature;
        uiKeys = keyObjects;
        uiLabels = labels.clone();
        uiRects = rects.clone();
        uiCount = n;
        final int keyCount = n;
        model = null; // until rebuilt: a gesture on the new layout stays stock
        handler.post(() -> {
            try {
                long t0 = SystemClock.uptimeMillis();
                KeyboardModel m = NintypeDecoder.buildModel(labels, rects, keyCount);
                if (m == null) {
                    if (ModSettings.debug()) Log.i(TAG, "two-thumb: layout without letters (" + keyCount + " keys), off on it");
                    return;
                }
                long t1 = SystemClock.uptimeMillis();
                String warm = decoder.warmUp(m);
                if (ModSettings.debug()) Log.i(TAG, "two-thumb: board from " + keyCount + " keys in " + (t1 - t0) + " ms, warm-up decode \""
                        + warm + "\" in " + (SystemClock.uptimeMillis() - t1) + " ms");
                model = m;
            } catch (Throwable t) {
                Log.e(TAG, "two-thumb: board build failed", t);
            }
        });
    }

    // =========================================================================================
    // Gate (UI thread)

    /**
     * Injected at the start of the main keyboard view's dispatch, f(breadcrumb, MotionEvent).
     *
     * @return The event SwiftKey dispatches, or null to swallow it (the hook then returns true).
     */
    public static MotionEvent filterTouch(View view, Object breadcrumb, MotionEvent event) {
        try {
            if (TwoThumbMode.isInjected(event)) return event;
            if (TwoThumbMode.handles(event)) return TwoThumbMode.filter(view, event);
            return event;
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: gate failed, back to stock for this event", t);
            return event;
        }
    }

    /** Decoder thread: the editor text before the cursor, context for the decode. */
    private static void readTextBefore() {
        textBefore = null;
        InputMethodService service = serviceRef.get();
        if (service == null || privateField(service)) return;
        InputConnection ic = service.getCurrentInputConnection();
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(64, 0);
        textBefore = text == null ? null : text.toString();
    }

    private static boolean privateField(InputMethodService service) {
        EditorInfo info = service.getCurrentInputEditorInfo();
        if (info == null) return true;
        int type = info.inputType, variation = type & EditorInfo.TYPE_MASK_VARIATION;
        return (type & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT
                && (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                || variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD);
    }

    // =========================================================================================
    // Substitution (Fluency's thread)

    /**
     * Injected in the candidate update callable right after Fluency's predict call.
     *
     * @param predictions SwiftKey's Predictions for this request.
     * @param type        The request type (enum, names kept: FLOW, FLOW_LIFT_OFF, ...).
     * @param request     The request object.
     * @return The Predictions SwiftKey continues with.
     */
    public static Object substitute(Object predictions, Object type, Object request) {
        try {
            // an open word's alternatives replace every result while it is open
            Object bar = TwoThumbMode.barPredictions();
            return bar != null ? bar : predictions;
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: substitution failed, SwiftKey's own result kept", t);
            return predictions;
        }
    }

    // =========================================================================================
    // Learning guard

    /**
     * Injected at the start of the Fluency trainer's learnFrom(..., Prediction).
     *
     * @return True to skip it: the prediction is one of the two-finger bar's own (its taps never
     * reach SwiftKey's commit; this is a backstop).
     */
    public static boolean skipLearning(Object prediction) {
        try {
            return prediction != null && TwoThumbMode.isBarPrediction(prediction);
        } catch (Throwable t) {
            return false;
        }
    }
}
