package app.revanced.extension.swiftkey.typing;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import app.revanced.extension.swiftkey.settings.ModSettings;
import app.revanced.extension.swiftkey.toolbar.ToolbarIcons;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unused")
public final class CapitalIPatch {
    private static final String TAG = "SwiftKeyMods";

    private static final String PREFERENCES_NAME = "revanced_swiftkey";
    private static final String PREFERENCE_KEY = "revanced_capitalize_i";

    // Must match TOOLBAR_ITEM_ID in CapitalIPatch.kt.
    public static final int TOOLBAR_ITEM_ID = 90;

    // Same telemetry slot as the autocorrect button; the item needs one.
    private static final String TELEMETRY_ITEM_NAME = "AUTOCORRECT";

    private static final String CAPTION = "Capital I";

    private static Context context;
    private static SharedPreferences preferences;

    // Read from the prediction thread, written from the UI thread.
    private static volatile boolean capitalize = true;

    /** Whether the toolbar toggle wants a lone "i" capitalised. */
    public static boolean isCapitalizeOn() {
        return capitalize;
    }

    // The Mods settings screen edits the same preference; held statically (weakly referenced).
    private static final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (changed, key) -> {
        if (PREFERENCE_KEY.equals(key)) capitalize = changed.getBoolean(PREFERENCE_KEY, true);
    };

    private static Method getInputMethod;
    private static Method getTermMethod;
    private static Method getEncodingsMethod;
    private static Constructor<?> termConstructor;

    /**
     * Injected at the start of KeyboardService.onCreate.
     */
    public static void init(Context service) {
        if (preferences != null) return;
        try {
            context = service.getApplicationContext() != null ? service.getApplicationContext() : service;
            // Device-protected so it also works before the first unlock.
            preferences = context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            capitalize = preferences.getBoolean(PREFERENCE_KEY, true);
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
            if (ModSettings.debug()) Log.d(TAG, "CapitalI init: capitalize=" + capitalize);
        } catch (Exception ex) {
            Log.e(TAG, "CapitalI init failed", ex);
        }
    }

    // region Predictions

    /**
     * "I", "I'm", "I’ll", "I am": a capital I on its own, not the start of a longer word like "It".
     */
    private static boolean startsWithLoneCapitalI(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != 'I') return false;
        if (text.length() == 1) return true;
        char next = text.charAt(1);
        return next == '\'' || next == '’' || Character.isWhitespace(next);
    }

    /**
     * Only undo the capital when the user actually typed a lowercase i (so shift + i still gives "I").
     */
    private static boolean typedLowercaseI(String input) {
        return input != null && !input.isEmpty() && input.charAt(0) == 'i';
    }

    private static String lowercaseFirst(String text) {
        // Same length, so the prediction's term breaks stay valid.
        return 'i' + text.substring(1);
    }

    private static String getInput(Object prediction) {
        try {
            if (getInputMethod == null) getInputMethod = prediction.getClass().getMethod("getInput");
            return (String) getInputMethod.invoke(prediction);
        } catch (Exception ex) {
            Log.e(TAG, "CapitalI getInput failed", ex);
            return null;
        }
    }

    /**
     * Injected in Prediction.getPrediction, after the native conversion and before it's cached.
     */
    public static String fixPrediction(Object prediction, String text) {
        if (capitalize || !startsWithLoneCapitalI(text)) return text;

        String input = getInput(prediction);
        String result = typedLowercaseI(input) ? lowercaseFirst(text) : text;
        if (ModSettings.debug()) Log.d(TAG, "CapitalI prediction input=\"" + input + "\" original=\"" + text + "\" result=\"" + result + "\"");
        return result;
    }

    /**
     * Injected in Prediction.get(int), after the native conversion and before it's cached.
     * Replaces the first Term in place, keeping its encodings.
     */
    public static void fixTerms(Object prediction, Object[] terms) {
        if (capitalize || terms == null || terms.length == 0 || terms[0] == null) return;

        Object term = terms[0];
        try {
            if (getTermMethod == null) {
                Class<?> termClass = term.getClass();
                getTermMethod = termClass.getMethod("getTerm");
                getEncodingsMethod = termClass.getMethod("getEncodings");
                termConstructor = termClass.getConstructor(Set.class, String.class);
            }

            String text = (String) getTermMethod.invoke(term);
            if (!startsWithLoneCapitalI(text)) return;

            String input = getInput(prediction);
            boolean fix = typedLowercaseI(input);
            if (ModSettings.debug()) Log.d(TAG, "CapitalI term input=\"" + input + "\" original=\"" + text + "\" fixed=" + fix);
            if (!fix) return;

            terms[0] = termConstructor.newInstance(getEncodingsMethod.invoke(term), lowercaseFirst(text));
        } catch (Exception ex) {
            Log.e(TAG, "CapitalI fixTerms failed", ex);
        }
    }

    // endregion

    // region Toolbar

    /**
     * Injected in the known toolbar items list's static initializer.
     */
    public static List<Integer> addToolbarItem(List<Integer> items) {
        List<Integer> list = new ArrayList<>(items);
        if (!list.contains(TOOLBAR_ITEM_ID)) list.add(TOOLBAR_ITEM_ID);
        return Collections.unmodifiableList(list);
    }

    private static int icon() {
        return capitalize ? ToolbarIcons.CAPITAL_I_ON : ToolbarIcons.CAPITAL_I_OFF;
    }

    private static String description() {
        return capitalize ? "Capital I on" : "Capital I off";
    }

    private static void toggle() {
        capitalize = !capitalize;
        if (preferences != null) preferences.edit().putBoolean(PREFERENCE_KEY, capitalize).apply();
        if (ModSettings.debug()) Log.d(TAG, "CapitalI toggled: capitalize=" + capitalize);
        if (context != null) {
            Toast.makeText(context, capitalize ? "Capital I: on" : "Capital I: off", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Called by ToolbarItems from the toolbar item factory, for this item's id.
     *
     * @param itemClass The on/off toolbar item class the autocorrect and incognito buttons use.
     */
    public static Object createToolbarItem(Class<?> itemClass) {
        return app.revanced.extension.swiftkey.toolbar.ToolbarItems.toggleItem(itemClass, TOOLBAR_ITEM_ID,
                CapitalIPatch::icon, CapitalIPatch::description, CAPTION, CapitalIPatch::toggle);
    }
    // endregion
}
