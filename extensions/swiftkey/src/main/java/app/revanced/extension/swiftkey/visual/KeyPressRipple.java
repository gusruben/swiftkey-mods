package app.revanced.extension.swiftkey.visual;

import app.revanced.extension.swiftkey.settings.ModSettings;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a rainbow ring that starts on the edge of a pressed key and grows outwards
 * while it thins away. Where the ring passes over other keys it is drawn faintly.
 * <p>
 * The ring lives in a Drawable on the KeyboardFrame's ViewGroupOverlay, so it sits above
 * the keys and the Flow trail and survives the frame swapping its keyboard view.
 * Everything runs on the UI thread.
 */
@SuppressWarnings("unused")
public final class KeyPressRipple {
    /**
     * Added to the keyboard view class (and so every key layout view) by the patch.
     * Keys are the app's own objects, passed through untyped.
     */
    public interface KeyboardView {
        /** Every key of the layout, in the order the view draws them. */
        List<?> patch_getRippleKeys();

        /** The key's drawable for the current theme (cached by the app). */
        Drawable patch_getRippleKeyDrawable(Object key);

        /** The key's area, normalised to the keyboard view (0 to 1). */
        RectF patch_getRippleKeyArea(Object key);

        /** The key's area minus the theme's background inset, normalised. */
        RectF patch_getRippleKeyBackground(Object key);
    }

    private static final String TAG = "KeyPressRipple";

    // The main keyboard view is a direct child of this. The long-press popup is not, so it gets no ripples.
    private static final String KEYBOARD_FRAME_CLASS = "com.touchtype.keyboard.view.frames.KeyboardFrame";

    private static final float DENSITY = Resources.getSystem().getDisplayMetrics().density;

    private static final long DURATION_MS = 350;
    private static final float GROW_PX = 10f * DENSITY;
    private static final float START_STROKE_PX = 3f * DENSITY;
    private static final float FALLBACK_RADIUS_PX = 6f * DENSITY;


    // Time for the rainbow to turn once around the key. Tied to the clock so all rings turn together.
    private static final long SPIN_MS = 1400;

    private static final int MAX_RIPPLES = 12;

    // A closed hue wheel for the SweepGradient (first and last colour match, so there is no seam).
    private static final int[] RAINBOW = new int[7];

    static {
        float[] hsv = {0f, 0.7f, 1f};
        for (int i = 0; i < RAINBOW.length; i++) {
            hsv[0] = (i * 60f) % 360f;
            RAINBOW[i] = Color.HSVToColor(hsv);
        }
    }

    // Set by onResolveKey when the event is a new pointer going down, read by onKeyResolved.
    private static boolean pendingDown;
    private static long pendingTime;
    private static int pendingPointerId;

    // The main keyboard can resolve a pointer more than once per event; ripple only once.
    private static long lastRippleTime = -1;
    private static int lastRipplePointerId = -1;

    private static WeakReference<ViewGroup> frameRef = new WeakReference<>(null);
    private static WeakReference<RippleOverlay> overlayRef = new WeakReference<>(null);

    private static final int MEASURE_NONE = 0;
    private static final int MEASURE_HIDDEN = 1;
    // Visible, measured from where the key last drew.
    private static final int MEASURE_DRAWN = 2;
    // Visible, measured from the layout because the key's current drawable hasn't drawn yet.
    private static final int MEASURE_LAYOUT = 3;

    // Visible background rects and corner radii of every key, in KeyboardFrame coordinates.
    private static RectF[] cachedRects = new RectF[0];
    private static float[][] cachedRadii = new float[0][];
    private static int[] cachedSources = new int[0];

    /**
     * Injected at the start of the key-for-pointer method. Runs for every pointer of every
     * touch event, so it only checks the action.
     *
     * @param event        The MotionEvent being handled.
     * @param pointerIndex Index of the pointer whose key is being resolved.
     */
    public static void onResolveKey(Object event, int pointerIndex) {
        pendingDown = false;
        if (!(event instanceof MotionEvent)) return;

        MotionEvent motionEvent = (MotionEvent) event;
        int action = motionEvent.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_POINTER_DOWN) return;
        if (pointerIndex != motionEvent.getActionIndex()) return;

        pendingDown = true;
        pendingTime = motionEvent.getEventTime();
        pendingPointerId = motionEvent.getPointerId(pointerIndex);
    }

    /**
     * Injected before every return of the key-for-pointer method.
     *
     * @param view The keyboard view the key belongs to.
     * @param key  The resolved key, or null when the pointer is not on a key.
     */
    public static void onKeyResolved(View view, Object key) {
        if (!ModSettings.keyRipple()) return;
        if (!pendingDown) return;
        pendingDown = false;
        if (key == null) return;
        if (pendingTime == lastRippleTime && pendingPointerId == lastRipplePointerId) return;

        try {
            if (startRipple(view, key)) {
                lastRippleTime = pendingTime;
                lastRipplePointerId = pendingPointerId;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not start the key press ripple", t);
        }
    }

    /**
     * A ripple for a touch SwiftKey never sees (two-thumb mode routes letter keys to Nintype).
     * Respects the Key press ripple switch.
     */
    public static void rippleKey(View view, Object key) {
        if (!ModSettings.keyRipple() || key == null) return;
        try {
            startRipple(view, key);
        } catch (Throwable t) {
            Log.e(TAG, "Could not start the key press ripple", t);
        }
    }

    private static boolean startRipple(View view, Object key) {
        ViewParent parent = view.getParent();
        if (parent == null || !KEYBOARD_FRAME_CLASS.equals(parent.getClass().getName())) return false;
        if (!(view instanceof KeyboardView)) return false;

        ViewGroup frame = (ViewGroup) parent;
        KeyboardView keyboard = (KeyboardView) view;

        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0 || frame.getWidth() <= 0 || frame.getHeight() <= 0) return false;

        // Key rects are relative to the keyboard view. With key child views (the usual mode) the
        // children are offset by the view's padding; when it draws keys itself they are not.
        float originX = view.getX();
        float originY = view.getY();
        if (view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 0) {
            originX += view.getPaddingLeft();
            originY += view.getPaddingTop();
        }

        RectF rect = new RectF();
        float[] radii = new float[8];
        int pressedSource = measureKey(keyboard, key, width, height, originX, originY, rect, radii);
        if (pressedSource == MEASURE_NONE) return false;

        List<?> keys = keyboard.patch_getRippleKeys();
        int pressedIndex = indexOfIdentity(keys, key);
        updateKeyCache(keyboard, keys, width, height, originX, originY, pressedIndex, rect, radii, pressedSource);

        RippleOverlay overlay = attachOverlay(frame);
        overlay.add(new Ripple(rect, radii, otherKeysPath(pressedIndex), SystemClock.uptimeMillis()));
        return true;
    }

    private static int indexOfIdentity(List<?> keys, Object key) {
        if (keys == null) return -1;
        for (int i = 0, size = keys.size(); i < size; i++) {
            if (keys.get(i) == key) return i;
        }
        return -1;
    }

    /**
     * Finds the key's visible background, in KeyboardFrame coordinates, and its corner radii.
     *
     * @return {@link #MEASURE_NONE} if the key has no usable size, else whether its background is visible.
     */
    private static int measureKey(KeyboardView keyboard, Object key, int width, int height,
                                  float originX, float originY, RectF outRect, float[] outRadii) {
        RectF area = keyboard.patch_getRippleKeyArea(key);
        if (area == null) return MEASURE_NONE;

        RectF areaPx = new RectF(originX + area.left * width, originY + area.top * height,
                originX + area.right * width, originY + area.bottom * height);
        if (areaPx.width() < 1f || areaPx.height() < 1f) return MEASURE_NONE;

        // Where SwiftKey's layout puts the background layer: only used for a key drawable
        // that hasn't drawn yet (KeyDrawHook records where each one really draws).
        RectF layoutBackground = null;
        RectF background = keyboard.patch_getRippleKeyBackground(key);
        if (background != null) {
            layoutBackground = new RectF(originX + background.left * width, originY + background.top * height,
                    originX + background.right * width, originY + background.bottom * height);
        }

        Drawable drawable = keyboard.patch_getRippleKeyDrawable(key);
        int flags = KeyDrawHook.visibleBackground(drawable, areaPx, layoutBackground, FALLBACK_RADIUS_PX,
                outRect, outRadii);
        if (outRect.width() < 1f || outRect.height() < 1f) return MEASURE_NONE;
        if ((flags & KeyDrawHook.VISIBLE) == 0) return MEASURE_HIDDEN;
        return (flags & KeyDrawHook.FROM_DRAW) != 0 ? MEASURE_DRAWN : MEASURE_LAYOUT;
    }

    /** Re-measures every other key: cheap, since theme backgrounds are measured once per size. */
    private static void updateKeyCache(KeyboardView keyboard, List<?> keys, int width, int height,
                                       float originX, float originY, int pressedIndex,
                                       RectF pressedRect, float[] pressedRadii, int pressedSource) {
        int size = keys == null ? 0 : keys.size();
        RectF[] rects = new RectF[size];
        float[][] radii = new float[size][];
        int[] sources = new int[size];

        for (int i = 0; i < size; i++) {
            if (i == pressedIndex) {
                rects[i] = new RectF(pressedRect);
                radii[i] = pressedRadii.clone();
                sources[i] = pressedSource;
                continue;
            }

            RectF rect = new RectF();
            float[] keyRadii = new float[8];
            try {
                int measured = measureKey(keyboard, keys.get(i), width, height, originX, originY, rect, keyRadii);
                // Keys whose theme background paints nothing don't dim the ring.
                if (measured == MEASURE_DRAWN || measured == MEASURE_LAYOUT) {
                    rects[i] = rect;
                    radii[i] = keyRadii;
                    sources[i] = measured;
                }
            } catch (Exception ignored) {
                // Leave this key out of the overlap mask.
            }
        }

        cachedRects = rects;
        cachedRadii = radii;
        cachedSources = sources;
    }

    /** The backgrounds of every key except the pressed one. */
    private static int lastPressedIndex = -1;

    private static Path otherKeysPath(int pressedIndex) {
        lastPressedIndex = pressedIndex;
        Path path = new Path();
        for (int i = 0; i < cachedRects.length; i++) {
            if (i == pressedIndex || cachedRects[i] == null) continue;
            path.addRoundRect(cachedRects[i], cachedRadii[i], Path.Direction.CW);
        }
        return path.isEmpty() ? null : path;
    }

    private static RippleOverlay attachOverlay(ViewGroup frame) {
        RippleOverlay overlay = overlayRef.get();
        if (overlay == null || frameRef.get() != frame || overlay.getCallback() == null) {
            ViewGroup oldFrame = frameRef.get();
            if (overlay != null && oldFrame != null) oldFrame.getOverlay().remove(overlay);

            overlay = new RippleOverlay();
            frame.getOverlay().add(overlay);
            overlayRef = new WeakReference<>(overlay);
            frameRef = new WeakReference<>(frame);
        }
        overlay.setBounds(0, 0, frame.getWidth(), frame.getHeight());
        return overlay;
    }

    private static final class Ripple {
        final RectF rect;
        final float[] radii;
        final Path otherKeys;
        final long start;
        final SweepGradient shader;
        final float centerX, centerY;

        Ripple(RectF rect, float[] radii, Path otherKeys, long start) {
            this.rect = rect;
            this.radii = radii;
            this.otherKeys = otherKeys;
            this.start = start;
            centerX = rect.centerX();
            centerY = rect.centerY();
            shader = new SweepGradient(centerX, centerY, RAINBOW, null);
        }
    }

    private static final class RippleOverlay extends Drawable {
        private final ArrayList<Ripple> ripples = new ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix shaderMatrix = new Matrix();
        private final Path ring = new Path();
        private final RectF ringRect = new RectF();
        private final float[] ringRadii = new float[8];

        RippleOverlay() {
            paint.setStyle(Paint.Style.STROKE);
        }

        void add(Ripple ripple) {
            if (ripples.size() >= MAX_RIPPLES) ripples.remove(0);
            ripples.add(ripple);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            try {
                long now = SystemClock.uptimeMillis();
                float spin = (now % SPIN_MS) * 360f / SPIN_MS;

                for (int i = 0; i < ripples.size(); ) {
                    Ripple ripple = ripples.get(i);
                    float progress = (now - ripple.start) / (float) DURATION_MS;
                    if (progress >= 1f) {
                        ripples.remove(i);
                        continue;
                    }
                    drawRipple(canvas, ripple, Math.max(progress, 0f), spin);
                    i++;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Could not draw the key press ripple", t);
                ripples.clear();
            }

            if (debugOutlines()) drawDebugOutlines(canvas);
            if (!ripples.isEmpty()) invalidateSelf();
        }

        // Debug aid: `adb shell setprop debug.swiftkeymods.ripple 1` outlines the measured key
        // backgrounds until the prop is cleared: magenta where measured from the key's last draw,
        // yellow where measured from the layout (that drawable hadn't drawn), cyan for the last pressed key.
        private final Paint debugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long debugCheckedAt;
        private boolean debugOn;

        private boolean debugOutlines() {
            long now = SystemClock.uptimeMillis();
            if (now - debugCheckedAt > 1000) {
                debugCheckedAt = now;
                try {
                    Object value = Class.forName("android.os.SystemProperties")
                            .getMethod("get", String.class).invoke(null, "debug.swiftkeymods.ripple");
                    debugOn = "1".equals(value);
                } catch (Throwable ignored) {
                    debugOn = false;
                }
            }
            return debugOn;
        }

        private void drawDebugOutlines(Canvas canvas) {
            debugPaint.setStyle(Paint.Style.STROKE);
            debugPaint.setStrokeWidth(2f);
            for (int i = 0; i < cachedRects.length; i++) {
                if (cachedRects[i] == null) continue;
                int source = i < cachedSources.length ? cachedSources[i] : MEASURE_DRAWN;
                debugPaint.setColor(i == lastPressedIndex ? 0xFF00FFFF
                        : source == MEASURE_LAYOUT ? 0xFFFFFF00 : 0xFFFF00FF);
                Path outline = new Path();
                outline.addRoundRect(cachedRects[i], cachedRadii[i], Path.Direction.CW);
                canvas.drawPath(outline, debugPaint);
            }
        }

        private void drawRipple(Canvas canvas, Ripple ripple, float progress, float spin) {
            float inverse = 1f - progress;
            float eased = 1f - inverse * inverse * inverse; // Ease-out cubic.

            // The ring shoots out early (eased) but thins over the whole duration, so it lingers a little.
            float stroke = START_STROKE_PX * inverse;
            // A zero-width stroke is a 1px hairline, so stop before it gets there.
            if (stroke < 0.5f) return;

            // The stroke is centred on the path: push the path out by half the stroke so the ring's
            // inner edge starts exactly on the key's edge. An outset rounded rect keeps a constant
            // distance from the key by growing each radius by the same amount.
            float offset = GROW_PX * eased + stroke / 2f;
            RectF rect = ripple.rect;
            ringRect.set(rect.left - offset, rect.top - offset, rect.right + offset, rect.bottom + offset);
            for (int i = 0; i < 8; i++) ringRadii[i] = ripple.radii[i] + offset;
            ring.reset();
            ring.addRoundRect(ringRect, ringRadii, Path.Direction.CW);

            shaderMatrix.setRotate(spin, ripple.centerX, ripple.centerY);
            ripple.shader.setLocalMatrix(shaderMatrix);
            paint.setShader(ripple.shader);
            paint.setStrokeWidth(stroke);

            // Fade the last sub-pixel of width instead of letting it snap off.
            int alpha = (int) (255 * Math.min(1f, stroke));

            int save = canvas.save();
            if (ripple.otherKeys != null) canvas.clipOutPath(ripple.otherKeys);
            paint.setAlpha(alpha);
            canvas.drawPath(ring, paint);
            canvas.restoreToCount(save);

            if (ripple.otherKeys != null) {
                save = canvas.save();
                canvas.clipPath(ripple.otherKeys);
                paint.setAlpha(alpha * ModSettings.rippleOverKeysAlpha() / 255);
                canvas.drawPath(ring, paint);
                canvas.restoreToCount(save);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
