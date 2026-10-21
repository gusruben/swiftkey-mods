package app.revanced.extension.swiftkey.settings;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;

import java.util.List;

/**
 * Adds the "Mods" entry to SwiftKey's home settings screen. The patch calls {@link #add} at the
 * end of the home fragment's onCreatePreferences, and adds the two interfaces below to androidx's
 * (obfuscated) Preference and PreferenceGroup, so this never names their members.
 */
@SuppressWarnings("unused")
public final class ModsHomeEntry {
    private static final String KEY = "revanced_mods_settings";
    // The entry goes right after "Layout & keys".
    private static final String AFTER_KEY = "pref_home_launch_layout_and_keys_prefs";

    public interface Preference {
        String patch_getKey();

        int patch_getOrder();

        /** Sets the order field only: the list sorts by it when it is built, after this runs. */
        void patch_setOrder(int order);

        /** Sets the fields directly, before the preference is attached. */
        void patch_setUp(String key, CharSequence title, Drawable icon, Intent intent);
    }

    public interface PreferenceGroup {
        Context patch_getContext();

        List<?> patch_getPreferences();

        /** A new plain Preference, as the XML inflater would make it (default preference style). */
        Object patch_newPreference();

        void patch_addPreference(Object preference);
    }

    public static void add(Object screen) {
        PreferenceGroup group = (PreferenceGroup) screen;
        Context context = group.patch_getContext();

        Preference entry = (Preference) group.patch_newPreference();
        entry.patch_setUp(KEY, "Mods", icon(context), ModsSettingsScreen.intent(context));

        // The XML entries are numbered in order, so make room after the anchor. Without it the
        // entry gets the next number when added, at the end.
        Integer after = null;
        for (Object child : group.patch_getPreferences()) {
            Preference preference = (Preference) child;
            if (AFTER_KEY.equals(preference.patch_getKey())) after = preference.patch_getOrder();
        }
        if (after != null) {
            for (Object child : group.patch_getPreferences()) {
                Preference preference = (Preference) child;
                int order = preference.patch_getOrder();
                if (order > after && order != Integer.MAX_VALUE) preference.patch_setOrder(order + 1);
            }
            entry.patch_setOrder(after + 1);
        }
        group.patch_addPreference(entry);
    }

    /**
     * Wrench outline in the style of SwiftKey's own settings icons (24dp, 1.5 stroke, tinted by the
     * list). The path is the vector's path data with its arcs converted to cubics, as VectorDrawable does.
     */
    private static Drawable icon(Context context) {
        Path path = new Path();
        path.moveTo(14.7f, 6.3f);
        path.cubicTo(14.517f, 6.487f, 14.414f, 6.738f, 14.414f, 7f);
        path.cubicTo(14.414f, 7.262f, 14.517f, 7.513f, 14.7f, 7.7f);
        path.lineTo(16.3f, 9.3f);
        path.cubicTo(16.487f, 9.483f, 16.738f, 9.586f, 17f, 9.586f);
        path.cubicTo(17.262f, 9.586f, 17.513f, 9.483f, 17.7f, 9.3f);
        path.lineTo(21.47f, 5.53f);
        path.cubicTo(21.973f, 6.641f, 22.125f, 7.879f, 21.906f, 9.079f);
        path.cubicTo(21.688f, 10.279f, 21.109f, 11.384f, 20.246f, 12.246f);
        path.cubicTo(19.384f, 13.109f, 18.279f, 13.688f, 17.079f, 13.906f);
        path.cubicTo(15.879f, 14.125f, 14.641f, 13.973f, 13.53f, 13.47f);
        path.lineTo(6.62f, 20.38f);
        path.cubicTo(6.222f, 20.776f, 5.683f, 20.997f, 5.121f, 20.997f);
        path.cubicTo(4.56f, 20.996f, 4.022f, 20.772f, 3.625f, 20.375f);
        path.cubicTo(3.228f, 19.978f, 3.004f, 19.44f, 3.003f, 18.879f);
        path.cubicTo(3.003f, 18.317f, 3.224f, 17.778f, 3.62f, 17.38f);
        path.lineTo(10.53f, 10.47f);
        path.cubicTo(10.027f, 9.359f, 9.875f, 8.121f, 10.094f, 6.921f);
        path.cubicTo(10.312f, 5.721f, 10.891f, 4.616f, 11.754f, 3.754f);
        path.cubicTo(12.616f, 2.891f, 13.721f, 2.312f, 14.921f, 2.094f);
        path.cubicTo(16.121f, 1.875f, 17.359f, 2.027f, 18.47f, 2.53f);
        path.lineTo(14.71f, 6.29f);
        path.close();

        // PathShape scales the 24 x 24 viewport (stroke included) to the bounds.
        ShapeDrawable drawable = new ShapeDrawable(new PathShape(path, 24f, 24f));
        Paint paint = drawable.getPaint();
        paint.setAntiAlias(true);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        int size = Math.round(24 * context.getResources().getDisplayMetrics().density);
        drawable.setIntrinsicWidth(size);
        drawable.setIntrinsicHeight(size);
        return drawable;
    }
}
