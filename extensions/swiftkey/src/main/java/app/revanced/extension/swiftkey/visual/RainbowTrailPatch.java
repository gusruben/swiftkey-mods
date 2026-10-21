package app.revanced.extension.swiftkey.visual;

import app.revanced.extension.swiftkey.settings.ModSettings;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.SystemClock;

@SuppressWarnings("unused")
public final class RainbowTrailPatch {
    // Trail length for one full trip around the hue wheel.
    private static final float DP_PER_CYCLE = 320f;

    // Time for the colours to cycle once at a fixed spot on the trail.
    private static final long MS_PER_CYCLE = 2400;

    private static final float PX_PER_CYCLE =
            DP_PER_CYCLE * Resources.getSystem().getDisplayMetrics().density;

    // The trail is drawn on the UI thread only, so shared state needs no locking.
    private static final float[] hsv = {0f, 0.85f, 1f};

    private static float pointDistance;

    /**
     * Injected at the start of each trail draw call.
     *
     * @param distance Path length in px from the start of the stroke to the point being drawn.
     */
    public static void setPointDistance(float distance) {
        pointDistance = distance;
    }

    /**
     * Injected before every Paint.setColor in the trail draw calls.
     * Keeps the alpha (the trail's fade-out) and replaces the colour with a hue
     * picked by position along the stroke, shifted over time so the bands flow
     * from the finger towards the tail.
     */
    public static int getTrailColor(int originalColor) {
        if (!ModSettings.rainbowTrail()) return originalColor;

        float time = (SystemClock.uptimeMillis() % MS_PER_CYCLE) / (float) MS_PER_CYCLE;
        float cycle = (pointDistance / PX_PER_CYCLE + time) % 1f;

        hsv[0] = cycle * 360f;
        return Color.HSVToColor(originalColor >>> 24, hsv);
    }
}
