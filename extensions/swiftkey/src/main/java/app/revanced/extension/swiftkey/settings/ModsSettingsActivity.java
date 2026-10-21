package app.revanced.extension.swiftkey.settings;

import android.app.ActionBar;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toolbar;

import java.util.Locale;

/**
 * The "Mods" settings screen, opened from SwiftKey's home settings. Built in code with
 * platform widgets so it doesn't depend on SwiftKey's (obfuscated) settings framework.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class ModsSettingsActivity extends Activity {
    private SharedPreferences preferences;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // DayNight follows the system dark mode; it needs API 29.
        setTheme(Build.VERSION.SDK_INT >= 29
                ? android.R.style.Theme_DeviceDefault_DayNight
                : android.R.style.Theme_DeviceDefault_Light);
        // Our own Toolbar instead of the window's action bar: on Android 15 the window is edge to
        // edge, so the window's bar overlapped the first rows. The root takes the system bar insets.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setTitle("Mods");

        preferences = ModSettings.preferences(this);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(24));

        header("Visuals");
        toggle("Rainbow swipe trail", "The swipe trail cycles through the rainbow.",
                ModSettings.RAINBOW_TRAIL, ModSettings.RAINBOW_TRAIL_DEFAULT);
        View rippleOpacity = slider("Ripple opacity under keys", ModSettings.RIPPLE_OVER_KEYS_OPACITY,
                0, 100, 5, ModSettings.RIPPLE_OVER_KEYS_OPACITY_DEFAULT, "%d%%", 1f);
        toggle("Key press ripple", null,
                ModSettings.KEY_RIPPLE, ModSettings.KEY_RIPPLE_DEFAULT, rippleOpacity);
        list.addView(rippleOpacity);

        View hintOpacity = slider("Hint opacity", ModSettings.WATERMARK_OPACITY,
                1, 40, 1, ModSettings.WATERMARK_OPACITY_DEFAULT, "%d%%", 1f);
        View hintSize = slider("Hint size", ModSettings.WATERMARK_SIZE,
                100, 800, 10, ModSettings.WATERMARK_SIZE_DEFAULT, "%.1f×", 100f);
        View hintOffsetX = slider("Hint offset from right", ModSettings.WATERMARK_OFFSET_X,
                -50, 80, 1, ModSettings.WATERMARK_OFFSET_X_DEFAULT, "%d%%", 1f);
        View hintOffsetY = slider("Hint offset from top", ModSettings.WATERMARK_OFFSET_Y,
                -50, 80, 1, ModSettings.WATERMARK_OFFSET_Y_DEFAULT, "%d%%", 1f);
        toggle("Watermark key hints", null,
                ModSettings.WATERMARK_HINTS, ModSettings.WATERMARK_HINTS_DEFAULT,
                hintOpacity, hintSize, hintOffsetX, hintOffsetY);
        list.addView(hintOpacity);
        list.addView(hintSize);
        list.addView(hintOffsetX);
        list.addView(hintOffsetY);

        header("Typing");
        toggle("Autocorrect \"i\" to \"I\"", null,
                ModSettings.CAPITALIZE_I, ModSettings.CAPITALIZE_I_DEFAULT);
        toggle("Two-finger swipe mode", null,
                ModSettings.TWO_THUMB_MODE, ModSettings.TWO_THUMB_MODE_DEFAULT);

        header("Debug");
        toggle("Debug logging", null,
                ModSettings.DEBUG_LOGGING, ModSettings.DEBUG_LOGGING_DEFAULT);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.addView(list);

        Toolbar toolbar = new Toolbar(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // Insets reach the content only when the window draws behind the system bars (edge to
        // edge, forced on Android 15); otherwise the window has already fitted them and they are 0.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = insets.getSystemWindowInsetLeft(), top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight(), bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                DisplayCutout cutout = insets.getDisplayCutout();
                left = Math.max(left, cutout.getSafeInsetLeft());
                top = Math.max(top, cutout.getSafeInsetTop());
                right = Math.max(right, cutout.getSafeInsetRight());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }
            view.setPadding(left, top, right, 0);
            scroll.setPadding(0, 0, 0, bottom);
            return insets;
        });
        setContentView(root);
        setActionBar(toolbar);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setTitle("Mods");
            bar.setDisplayHomeAsUpEnabled(true);
        }
        root.requestApplyInsets();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void header(String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(themeColor(android.R.attr.colorAccent));
        view.setPadding(dp(16), dp(20), dp(16), dp(4));
        list.addView(view);
    }

    /**
     * Adds a switch row. Dependents are enabled only while the switch is on.
     */
    private void toggle(String title, String summary, String key, boolean fallback, View... dependents) {
        list.addView(toggleRow(title, summary, key, fallback, dependents));
    }

    /** A switch row, not yet added. Dependents are enabled only while the switch is on. */
    private View toggleRow(String title, String summary, String key, boolean fallback, View... dependents) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(label(title, 16, android.R.attr.textColorPrimary));
        if (summary != null) text.addView(label(summary, 14, android.R.attr.textColorSecondary));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        boolean on = preferences.getBoolean(key, fallback);
        toggle.setChecked(on);
        setEnabled(on, dependents);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(key, checked).apply();
            setEnabled(checked, dependents);
        });
        row.addView(toggle);
        row.setOnClickListener(v -> toggle.toggle());
        return row;
    }

    /**
     * Builds (but doesn't add) a slider row. Stored as an int; shown as value / divisor.
     */
    private View slider(String title, String key, int min, int max, int step, int fallback,
                        String format, float divisor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(4), dp(16), dp(12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = label(title, 15, android.R.attr.textColorPrimary);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = label("", 15, android.R.attr.textColorSecondary);
        top.addView(value);
        row.addView(top);

        SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        int current = Math.max(min, Math.min(max, preferences.getInt(key, fallback)));
        bar.setProgress((current - min) / step);
        value.setText(formatValue(format, current, divisor));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int stored = min + progress * step;
                value.setText(formatValue(format, stored, divisor));
                if (fromUser) preferences.edit().putInt(key, stored).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        row.addView(bar);
        return row;
    }

    private static String formatValue(String format, int stored, float divisor) {
        return divisor == 1f
                ? String.format(Locale.US, format, stored)
                : String.format(Locale.US, format, stored / divisor);
    }

    private static void setEnabled(boolean enabled, View... views) {
        for (View view : views) setEnabledDeep(view, enabled);
    }

    private static void setEnabledDeep(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.4f);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setEnabled(enabled);
        }
    }

    private TextView label(String text, int sp, int colorAttr) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(themeColor(colorAttr));
        return view;
    }

    private int themeColor(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        if (value.resourceId != 0) return getResources().getColor(value.resourceId, getTheme());
        return value.data;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
