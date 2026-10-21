package app.revanced.extension.swiftkey.visual;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;

/**
 * Swipe trails for touches SwiftKey never sees (two-thumb mode routes letter keys to Nintype, so
 * SwiftKey draws no trail for them). One fading polyline per pointer on the keyboard frame's
 * overlay, coloured by {@link RainbowTrailPatch} when the Rainbow swipe trail switch is on.
 * UI thread only.
 */
public final class TouchTrail {
    private static final float DENSITY = Resources.getSystem().getDisplayMetrics().density;
    private static final float WIDTH_PX = 5f * DENSITY;
    private static final long FADE_MS = 280;
    private static final int MAX_POINTERS = 32;
    private static final int CAPACITY = 128;
    // Plain trail colour (Rainbow swipe trail off): a light blue that reads on dark and light themes.
    private static final int BASE_COLOR = 0xFF5B9BFF;

    private static final class Stroke {
        final float[] x = new float[CAPACITY], y = new float[CAPACITY], d = new float[CAPACITY];
        final long[] t = new long[CAPACITY];
        int start, size;
        boolean live;

        void add(float px, float py, long time) {
            float dist = 0;
            if (size > 0) {
                int last = (start + size - 1) % CAPACITY;
                dist = d[last] + (float) Math.hypot(px - x[last], py - y[last]);
            }
            int i;
            if (size < CAPACITY) i = (start + size++) % CAPACITY;
            else { i = start; start = (start + 1) % CAPACITY; }
            x[i] = px; y[i] = py; d[i] = dist; t[i] = time;
        }
    }

    private static final Stroke[] strokes = new Stroke[MAX_POINTERS];
    private static WeakReference<ViewGroup> frameRef = new WeakReference<>(null);
    private static WeakReference<TrailDrawable> drawableRef = new WeakReference<>(null);

    private TouchTrail() {
    }

    /** A sample of pointer [id] in [view]'s coordinates. [down] starts a new stroke. */
    public static void add(View view, int id, float x, float y, boolean down) {
        if (id < 0 || id >= MAX_POINTERS) return;
        TrailDrawable drawable = attach(view);
        if (drawable == null) return;
        Stroke s = strokes[id];
        if (s == null) s = strokes[id] = new Stroke();
        if (down) { s.start = 0; s.size = 0; }
        s.live = true;
        s.add(x + view.getX(), y + view.getY(), SystemClock.uptimeMillis());
        drawable.invalidateSelf();
    }

    /** Pointer [id] lifted: its stroke fades out. */
    public static void end(int id) {
        if (id >= 0 && id < MAX_POINTERS && strokes[id] != null) strokes[id].live = false;
    }

    private static TrailDrawable attach(View view) {
        ViewParent parent = view.getParent();
        if (!(parent instanceof ViewGroup)) return null;
        ViewGroup frame = (ViewGroup) parent;
        TrailDrawable drawable = drawableRef.get();
        if (drawable == null || frameRef.get() != frame || drawable.getCallback() == null) {
            ViewGroup old = frameRef.get();
            if (drawable != null && old != null) old.getOverlay().remove(drawable);
            drawable = new TrailDrawable();
            frame.getOverlay().add(drawable);
            drawableRef = new WeakReference<>(drawable);
            frameRef = new WeakReference<>(frame);
        }
        drawable.setBounds(0, 0, frame.getWidth(), frame.getHeight());
        return drawable;
    }

    private static final class TrailDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TrailDrawable() {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            boolean visible = false;
            for (Stroke s : strokes) {
                if (s == null || s.size < 2) continue;
                // A live stroke fades by age; a lifted one keeps fading until gone.
                for (int k = 1; k < s.size; k++) {
                    int a = (s.start + k - 1) % CAPACITY, b = (s.start + k) % CAPACITY;
                    long age = now - s.t[b];
                    if (age >= FADE_MS) continue;
                    float f = 1f - age / (float) FADE_MS;
                    visible = true;
                    RainbowTrailPatch.setPointDistance(s.d[b]);
                    int color = RainbowTrailPatch.getTrailColor((Math.round(f * 220) << 24) | (BASE_COLOR & 0xFFFFFF));
                    paint.setColor(color);
                    paint.setStrokeWidth(WIDTH_PX * (0.4f + 0.6f * f));
                    canvas.drawLine(s.x[a], s.y[a], s.x[b], s.y[b], paint);
                }
            }
            if (visible) invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
