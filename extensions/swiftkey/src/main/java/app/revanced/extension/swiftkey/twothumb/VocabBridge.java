package app.revanced.extension.swiftkey.twothumb;

import android.content.Context;
import android.os.Handler;
import android.os.UserManager;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import app.revanced.extension.swiftkey.settings.ModSettings;
import app.revanced.extension.swiftkey.twothumb.nintype.NintypeDecoder;

/**
 * One vocabulary for SwiftKey and two-thumb mode, through Fluency's public Trainer API on
 * Fluency's own thread ({@link #onFluencyTask}, injected at the start of SwiftKey's candidate
 * update task):
 * <ul>
 *   <li>SwiftKey to Nintype: once per process, SwiftKey's personal words
 *   ({@code Trainer.getNovelTerms(TagSelectors.allModels())}, as SwiftKey's own sync code reads them)
 *   go into Nintype's learned words.</li>
 *   <li>Nintype to SwiftKey: each word committed in two-thumb mode is added to SwiftKey's persistent
 *   user model ({@code Trainer.addSequence}, as SwiftKey's "add to user model" task does), only while
 *   SwiftKey's own "user model writable" check passes and never in password or no-learning fields.</li>
 * </ul>
 * Since SwiftKey's user model (files/language_models/user/dynamic.lm) is kept and backed up, a
 * two-thumb word also comes back into Nintype at the next start. Nintype's own learned state (the
 * trie port's LearnedStore) lives in files/nintype_learned/, backed up by Enable backups.
 */
@SuppressWarnings("unused")
public final class VocabBridge {
    private static final String TAG = "SwiftKeyMods";
    private static final int MAX_IMPORT = 50_000;
    private static final long SAVE_DELAY_MS = 5_000;

    private static final ConcurrentLinkedQueue<String> toSwiftKey = new ConcurrentLinkedQueue<>();
    private static volatile boolean imported;
    private static volatile boolean failed;

    // Fluency, by its public names (the native library binds to them, so they are not obfuscated).
    private static Method getTrainer, getNovelTerms, addSequence, getTerm, append;
    private static Object allModels, persistentModels;
    private static Constructor<?> sequenceCtor, termCtor;
    private static Field predictorField, sessionField;

    private static boolean saveScheduled;

    private VocabBridge() {
    }

    // =========================================================================================
    // Fluency thread

    /**
     * Injected at the start of the candidate update task, on Fluency's thread.
     *
     * @param predictor The task's predictor context (holds the Fluency session).
     * @param writable  SwiftKey's own "user model writable" check (false e.g. in incognito).
     */
    public static void onFluencyTask(Object predictor, boolean writable) {
        // the apostrophe tiebreak scores later in this same task (TwoThumbSwipe.substitute)
        taskPredictor = predictor;
        if (failed || (imported && toSwiftKey.isEmpty())) return;
        try {
            Object trainer = trainer(predictor);
            if (trainer == null) return;
            if (!imported && TwoThumbSwipe.decoder() != null) {
                imported = true;
                importFrom(trainer);
            }
            String word;
            int added = 0;
            while ((word = toSwiftKey.poll()) != null) {
                if (!writable) continue;
                Object sequence = sequenceCtor.newInstance();
                append.invoke(sequence, termCtor.newInstance(word));
                addSequence.invoke(trainer, sequence, persistentModels);
                added++;
            }
            if (ModSettings.debug()) {
                if (added > 0) Log.i(TAG, "two-thumb: taught SwiftKey " + added + " word(s)");
                else if (!writable && !imported) Log.i(TAG, "two-thumb: SwiftKey's user model is read-only, not teaching it");
            }
        } catch (Throwable t) {
            failed = true;
            Log.e(TAG, "two-thumb: vocabulary sharing failed, off until restart", t);
        }
    }

    private static Object trainer(Object predictor) throws Exception {
        Object session = session(predictor);
        return session == null ? null : getTrainer.invoke(session);
    }

    /** The Fluency Session behind the task's predictor context (whatever the obfuscated names). */
    private static synchronized Object session(Object predictor) throws Exception {
        if (getTrainer == null) {
            ClassLoader loader = predictor.getClass().getClassLoader();
            Class<?> session = Class.forName("com.microsoft.fluency.Session", false, loader);
            Class<?> trainerClass = Class.forName("com.microsoft.fluency.Trainer", false, loader);
            Class<?> sequence = Class.forName("com.microsoft.fluency.Sequence", false, loader);
            Class<?> term = Class.forName("com.microsoft.fluency.Term", false, loader);
            Class<?> tagSelector = Class.forName("com.microsoft.fluency.TagSelector", false, loader);
            Class<?> tagSelectors = Class.forName("com.microsoft.fluency.TagSelectors", false, loader);
            getTrainer = session.getMethod("getTrainer");
            getNovelTerms = trainerClass.getMethod("getNovelTerms", tagSelector);
            addSequence = trainerClass.getMethod("addSequence", sequence, tagSelector);
            getTerm = term.getMethod("getTerm");
            append = sequence.getMethod("append", term);
            sequenceCtor = sequence.getConstructor();
            termCtor = term.getConstructor(String.class);
            allModels = tagSelectors.getMethod("allModels").invoke(null);
            persistentModels = tagSelectors.getMethod("persistentDynamicModels").invoke(null);
            // predictor -> its model holder -> the session (whatever the obfuscated names).
            outer:
            for (Field f : predictor.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object holder = f.get(predictor);
                if (holder == null) continue;
                for (Field g : holder.getClass().getDeclaredFields()) {
                    if (!session.isAssignableFrom(g.getType())) continue;
                    g.setAccessible(true);
                    predictorField = f;
                    sessionField = g;
                    break outer;
                }
            }
            if (sessionField == null) throw new IllegalStateException("No Fluency session reachable from " + predictor.getClass());
        }
        Object holder = predictorField.get(predictor);
        return holder == null ? null : sessionField.get(holder);
    }

    // =========================================================================================
    // Apostrophe tiebreak scoring (Fluency thread, research/fluency-context-scoring.md)

    /** The predictor context of the candidate update task running now (Fluency thread). */
    private static volatile Object taskPredictor;
    private static Method getTokenizer, getSessionPredictor, splitContext, getContext, getPredictions,
            addCharacter, getPredictionText, getProbability, predictionSize;
    private static Constructor<?> touchHistoryCtor;
    private static Object scoringFilter;
    private static volatile boolean scoringFailed;
    private static String lastContextText;
    private static Object lastContext, lastSession;

    /** Whether Fluency can score now: on Fluency's thread, inside a candidate update task. */
    static boolean canScore() {
        return taskPredictor != null && !scoringFailed;
    }

    /**
     * [word]'s probability as the current word after [contextText] (the text before the word),
     * from SwiftKey's own models; -1 when Fluency does not return it as a single-term prediction.
     * Fluency's thread only.
     */
    static double score(String contextText, String word) throws Exception {
        Object predictor = taskPredictor;
        if (predictor == null || scoringFailed) return -1;
        try {
            Object session = session(predictor);
            if (session == null) return -1;
            if (getPredictions == null) {
                ClassLoader loader = predictor.getClass().getClassLoader();
                Class<?> sessionClass = Class.forName("com.microsoft.fluency.Session", false, loader);
                Class<?> tokenizer = Class.forName("com.microsoft.fluency.Tokenizer", false, loader);
                Class<?> ccw = Class.forName("com.microsoft.fluency.ContextCurrentWord", false, loader);
                Class<?> predictorClass = Class.forName("com.microsoft.fluency.Predictor", false, loader);
                Class<?> sequence = Class.forName("com.microsoft.fluency.Sequence", false, loader);
                Class<?> touchHistory = Class.forName("com.microsoft.fluency.TouchHistory", false, loader);
                Class<?> filter = Class.forName("com.microsoft.fluency.ResultsFilter", false, loader);
                Class<?> prediction = Class.forName("com.microsoft.fluency.Prediction", false, loader);
                Class<?> capHint = Class.forName("com.microsoft.fluency.ResultsFilter$CapitalizationHint", false, loader);
                Class<?> verbatim = Class.forName("com.microsoft.fluency.ResultsFilter$VerbatimMode", false, loader);
                Class<?> predMode = Class.forName("com.microsoft.fluency.ResultsFilter$PredictionMode", false, loader);
                Class<?> corrMode = Class.forName("com.microsoft.fluency.ResultsFilter$CorrectionMode", false, loader);
                Class<?> searchType = Class.forName("com.microsoft.fluency.ResultsFilter$PredictionSearchType", false, loader);
                getTokenizer = sessionClass.getMethod("getTokenizer");
                getSessionPredictor = sessionClass.getMethod("getPredictor");
                splitContext = tokenizer.getMethod("splitContextCurrentWord", String.class, int.class);
                getContext = ccw.getMethod("getContext");
                touchHistoryCtor = touchHistory.getConstructor();
                addCharacter = touchHistory.getMethod("addCharacter", String.class, float.class);
                getPredictionText = prediction.getMethod("getPrediction");
                getProbability = prediction.getMethod("getProbability");
                predictionSize = prediction.getMethod("size");
                scoringFilter = filter.getConstructor(int.class, capHint, verbatim, predMode, corrMode, searchType).newInstance(8,
                        enumValue(capHint, "DEFAULT"), enumValue(verbatim, "ENABLED"),
                        enumValue(predMode, "CURRENT_WORD_PREDICT"), enumValue(corrMode, "DEFAULT"),
                        enumValue(searchType, "NORMAL"));
                getPredictions = predictorClass.getMethod("getPredictions", sequence, touchHistory, filter);
            }
            if (!contextText.equals(lastContextText) || lastContext == null || lastSession != session) {
                lastSession = session;
                Object tok = getTokenizer.invoke(session);
                lastContext = getContext.invoke(splitContext.invoke(tok, contextText, contextText.length()));
                lastContextText = contextText;
            }
            Object th = touchHistoryCtor.newInstance();
            for (int i = 0; i < word.length(); ) {
                int cp = word.codePointAt(i);
                i += Character.charCount(cp);
                addCharacter.invoke(th, new String(Character.toChars(cp)), 1.0f);
            }
            Object preds = getPredictions.invoke(getSessionPredictor.invoke(session), lastContext, th, scoringFilter);
            if (!(preds instanceof Iterable)) return -1;
            String want = ApostropheTiebreak.norm(word);
            for (Object p : (Iterable<?>) preds) {
                if ((Integer) predictionSize.invoke(p) != 1) continue;
                String text = (String) getPredictionText.invoke(p);
                if (text != null && ApostropheTiebreak.norm(text).equals(want)) return (Double) getProbability.invoke(p);
            }
            return -1;
        } catch (Throwable t) {
            scoringFailed = true;
            Log.e(TAG, "two-thumb: Fluency scoring failed, smart apostrophes off until restart", t);
            return -1;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    private static void importFrom(Object trainer) throws Exception {
        Map<?, ?> novel = (Map<?, ?>) getNovelTerms.invoke(trainer, allModels);
        ArrayList<String> words = new ArrayList<>();
        if (novel != null) for (Object term : novel.keySet()) {
            String w = (String) getTerm.invoke(term);
            if (isWord(w)) words.add(w);
            if (words.size() >= MAX_IMPORT) break;
        }
        final String[] list = words.toArray(new String[0]);
        Handler h = TwoThumbSwipe.handler();
        if (h == null) return;
        h.post(() -> {
            try {
                NintypeDecoder d = TwoThumbSwipe.decoder();
                if (d == null) return;
                long t0 = System.currentTimeMillis();
                int n = d.learnWords(list);
                if (ModSettings.debug()) Log.i(TAG, "two-thumb: Nintype learned " + n + " of SwiftKey's " + (novel == null ? 0 : novel.size())
                        + " personal words in " + (System.currentTimeMillis() - t0) + " ms");
            } catch (Throwable t) {
                Log.e(TAG, "two-thumb: importing SwiftKey's words failed", t);
            }
        });
    }

    private static boolean isWord(String w) {
        if (w == null || w.length() < 2 || w.length() > 32) return false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!Character.isLetter(c) && c != '\'' && c != '’' && c != '-') return false;
        }
        return true;
    }

    // =========================================================================================
    // Two-thumb side

    /** A word committed in two-thumb mode (UI thread). */
    static void onCommitted(String word) {
        if (!TwoThumbSwipe.learningAllowed() || !isWord(word)) return;
        toSwiftKey.add(word);
        scheduleSave();
    }

    // =========================================================================================
    // Nintype's learned state on disk (the trie port's LearnedStore, Nintype's own file formats)

    /** files/nintype_learned (credential-protected, so null before the first unlock). */
    static File learnedDir(Context context) {
        UserManager um = context.getSystemService(UserManager.class);
        if (um != null && !um.isUserUnlocked()) return null;
        return new File(context.getFilesDir(), "nintype_learned");
    }

    /** After commits, debounced; on keyboard hide and destroy, now. */
    static void scheduleSave() {
        Handler h = TwoThumbSwipe.handler();
        if (h == null || saveScheduled) return;
        saveScheduled = true;
        h.postDelayed(VocabBridge::save, SAVE_DELAY_MS);
    }

    static void saveNow() {
        Handler h = TwoThumbSwipe.handler();
        if (h != null) h.post(VocabBridge::save);
    }

    private static void save() {
        saveScheduled = false;
        NintypeDecoder d = TwoThumbSwipe.decoder();
        if (d == null || d.getLearnedDir() == null) return;
        try {
            boolean saved = d.saveLearned();
            if (ModSettings.debug()) {
                if (saved) Log.i(TAG, "two-thumb: Nintype learned state saved");
                else Log.w(TAG, "two-thumb: saving Nintype's learned state failed (details in the \"nintype learned\" lines)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "two-thumb: saving Nintype's learned state failed", t);
        }
    }
}
