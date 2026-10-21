package app.revanced.extension.swiftkey.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Settings for the mod patches, shown on the "Mods" screen ({@link ModsSettingsActivity}).
 * Stored in device-protected storage so the keyboard can read them before the first unlock.
 * Values are cached and refreshed by a change listener, so the keyboard picks up edits live.
 */
@SuppressWarnings("unused")
public final class ModSettings {
    private static final String TAG = "SwiftKeyMods";

    // Shared with CapitalIPatch, which keeps its own copy of the "Capital I" toggle.
    public static final String PREFERENCES_NAME = "revanced_swiftkey";

    public static final String RAINBOW_TRAIL = "revanced_rainbow_trail";
    public static final String KEY_RIPPLE = "revanced_key_ripple";
    /** Percent. */
    public static final String RIPPLE_OVER_KEYS_OPACITY = "revanced_ripple_over_keys_opacity";
    public static final String WATERMARK_HINTS = "revanced_watermark_hints";
    /** Percent. */
    public static final String WATERMARK_OPACITY = "revanced_watermark_opacity";
    /** Percent of the stock hint size. */
    public static final String WATERMARK_SIZE = "revanced_watermark_size";
    /** Percent of the key width, in from the right edge. Temporary tuning knob. */
    public static final String WATERMARK_OFFSET_X = "revanced_watermark_offset_x";
    /** Percent of the key height, down from the top edge. Temporary tuning knob. */
    public static final String WATERMARK_OFFSET_Y = "revanced_watermark_offset_y";
    public static final String CAPITALIZE_I = "revanced_capitalize_i";
    /** Two-finger swipe mode: Nintype's input model on every letter key (toolbar button + Mods). */
    public static final String TWO_THUMB_MODE = "revanced_two_thumb_mode";
    /** Log lines under SwiftKeyMods (they can contain what you type). */
    public static final String DEBUG_LOGGING = "revanced_debug_logging";

    public static final boolean RAINBOW_TRAIL_DEFAULT = true;
    public static final boolean KEY_RIPPLE_DEFAULT = true;
    public static final int RIPPLE_OVER_KEYS_OPACITY_DEFAULT = 55;
    public static final boolean WATERMARK_HINTS_DEFAULT = true;
    public static final int WATERMARK_OPACITY_DEFAULT = 5;
    public static final int WATERMARK_SIZE_DEFAULT = 400;
    public static final int WATERMARK_OFFSET_X_DEFAULT = 0;
    public static final int WATERMARK_OFFSET_Y_DEFAULT = 10;
    public static final boolean CAPITALIZE_I_DEFAULT = true;
    public static final boolean TWO_THUMB_MODE_DEFAULT = false;
    public static final boolean DEBUG_LOGGING_DEFAULT = false;

    private static SharedPreferences preferences;

    // Held statically: SharedPreferences only keeps weak references to its listeners.
    private static final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (changed, key) -> load(changed);

    private static volatile boolean rainbowTrail = RAINBOW_TRAIL_DEFAULT;
    private static volatile boolean keyRipple = KEY_RIPPLE_DEFAULT;
    private static volatile int rippleOverKeysOpacity = RIPPLE_OVER_KEYS_OPACITY_DEFAULT;
    private static volatile boolean watermarkHints = WATERMARK_HINTS_DEFAULT;
    private static volatile int watermarkOpacity = WATERMARK_OPACITY_DEFAULT;
    private static volatile int watermarkSize = WATERMARK_SIZE_DEFAULT;
    private static volatile int watermarkOffsetX = WATERMARK_OFFSET_X_DEFAULT;
    private static volatile int watermarkOffsetY = WATERMARK_OFFSET_Y_DEFAULT;
    private static volatile boolean twoThumbMode = TWO_THUMB_MODE_DEFAULT;
    private static volatile boolean debugLogging = DEBUG_LOGGING_DEFAULT;

    private ModSettings() {
    }

    /** Injected at the start of KeyboardService.onCreate. */
    public static void init(Context context) {
        try {
            preferences(context);
        } catch (Throwable t) {
            Log.e(TAG, "Mod settings init failed", t);
        }
    }

    public static synchronized SharedPreferences preferences(Context context) {
        if (preferences == null) {
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            preferences = app.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
            preferences.registerOnSharedPreferenceChangeListener(listener);
            load(preferences);
        }
        return preferences;
    }

    private static void load(SharedPreferences p) {
        rainbowTrail = p.getBoolean(RAINBOW_TRAIL, RAINBOW_TRAIL_DEFAULT);
        keyRipple = p.getBoolean(KEY_RIPPLE, KEY_RIPPLE_DEFAULT);
        rippleOverKeysOpacity = p.getInt(RIPPLE_OVER_KEYS_OPACITY, RIPPLE_OVER_KEYS_OPACITY_DEFAULT);
        watermarkHints = p.getBoolean(WATERMARK_HINTS, WATERMARK_HINTS_DEFAULT);
        watermarkOpacity = p.getInt(WATERMARK_OPACITY, WATERMARK_OPACITY_DEFAULT);
        watermarkSize = p.getInt(WATERMARK_SIZE, WATERMARK_SIZE_DEFAULT);
        watermarkOffsetX = p.getInt(WATERMARK_OFFSET_X, WATERMARK_OFFSET_X_DEFAULT);
        watermarkOffsetY = p.getInt(WATERMARK_OFFSET_Y, WATERMARK_OFFSET_Y_DEFAULT);
        twoThumbMode = p.getBoolean(TWO_THUMB_MODE, TWO_THUMB_MODE_DEFAULT);
        debugLogging = p.getBoolean(DEBUG_LOGGING, DEBUG_LOGGING_DEFAULT);
    }

    /**
     * Mods "Debug logging". Every Log.v/d/i/w call in the mods runs only while this is on: they can
     * contain what you type. Log.e (real failures, no text) stays unconditional.
     */
    public static boolean debug() {
        return debugLogging;
    }

    /** Two-finger swipe mode. Off = SwiftKey's stock touch path. */
    public static boolean twoThumbMode() {
        return twoThumbMode;
    }

    public static boolean rainbowTrail() {
        return rainbowTrail;
    }

    public static boolean keyRipple() {
        return keyRipple;
    }

    /** 0 to 255. */
    public static int rippleOverKeysAlpha() {
        return Math.round(rippleOverKeysOpacity * 255f / 100f);
    }

    public static boolean watermarkHints() {
        return watermarkHints;
    }

    /** 0 to 1. */
    public static float watermarkOpacity() {
        return watermarkOpacity / 100f;
    }

    /** Multiple of the stock hint size. */
    public static float watermarkSize() {
        return watermarkSize / 100f;
    }

    /** Fraction of the key width. */
    public static float watermarkOffsetX() {
        return watermarkOffsetX / 100f;
    }

    /** Fraction of the key height. */
    public static float watermarkOffsetY() {
        return watermarkOffsetY / 100f;
    }
}
