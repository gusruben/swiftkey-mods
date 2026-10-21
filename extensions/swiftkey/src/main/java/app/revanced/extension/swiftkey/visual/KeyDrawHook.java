package app.revanced.extension.swiftkey.visual;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.view.Gravity;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Sits in the draw call of SwiftKey's key drawable (a LayerDrawable: layer 0 is the theme's
 * key background, layer 1 the label), so other patches see each key as it is actually drawn.
 * <p>
 * Every key with the same style shares one theme background Drawable instance, so its bounds
 * belong to whichever key set them last. The patch records each key's background bounds when
 * that key draws, and measures where the theme visibly paints inside them: theme shapes are
 * often a LayerDrawable with inset layers, and image themes (Fluent, for one) use 9-patches
 * with transparent, shadowed margins.
 * <p>
 * Everything runs on the UI thread.
 */
@SuppressWarnings("unused")
public final class KeyDrawHook {
    /** Added to the key drawable class by the patch. */
    public interface KeyDrawable {
        Object patch_getKeyDrawState();

        void patch_setKeyDrawState(Object state);

        /** The stock LayerDrawable draw (every layer in order). */
        void patch_drawLayers(Canvas canvas);
    }

    private static final String TAG = "KeyDrawHook";

    /** Set in {@link #visibleBackground}'s result when the theme paints something there. */
    public static final int VISIBLE = 1;
    /** Set when the geometry came from the key's last draw rather than from its layout. */
    public static final int FROM_DRAW = 2;

    // Rasterised backgrounds are measured at no more than this many pixels.
    private static final int MAX_RASTER_PIXELS = 160 * 160;

    private static boolean loggedError;

    /** Per key drawable. */
    static final class State {
        // As last drawn, in the drawable's own coordinates.
        boolean drawn;
        final Rect bounds = new Rect();
        final Rect background = new Rect();
        Drawable backgroundDrawable;

        // The last shape looked up for the drawn background, so a redraw skips the cache.
        Shape shape;
        Drawable shapeDrawable;
        int shapeWidth, shapeHeight;

        Shape drawnShape() {
            int width = background.width();
            int height = background.height();
            if (shape == null || shapeDrawable != backgroundDrawable || shapeWidth != width || shapeHeight != height) {
                shape = shapeOf(backgroundDrawable, width, height);
                shapeDrawable = backgroundDrawable;
                shapeWidth = width;
                shapeHeight = height;
            }
            return shape;
        }

        // Owned by KeyHintWatermark.
        Drawable hint;
        Object watermark;
    }

    /** Where the theme visibly paints inside a drawable's bounds. Insets are in px. */
    static final class Shape {
        static final Shape INVISIBLE = new Shape(false, 0, 0, 0, 0, null);

        final boolean visible;
        final float left, top, right, bottom;
        /** Corner radii in Path order (x, y pairs from top left, clockwise), or null if unknown. */
        final float[] radii;

        Shape(boolean visible, float left, float top, float right, float bottom, float[] radii) {
            this.visible = visible;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.radii = radii;
        }
    }

    /**
     * Replaces the key drawable's draw.
     */
    public static void draw(LayerDrawable drawable, Canvas canvas) {
        try {
            if (drawable.getNumberOfLayers() > 0) {
                State state = stateOf(drawable, true);
                if (state != null) {
                    state.drawn = true;
                    state.bounds.set(drawable.getBounds());
                    Drawable background = drawable.getDrawable(0);
                    state.backgroundDrawable = background;
                    if (background != null) state.background.set(background.getBounds());

                    if (state.hint != null && KeyHintWatermark.drawLayers(drawable, canvas, state)) return;
                }
            }
        } catch (Throwable t) {
            if (!loggedError) {
                loggedError = true;
                Log.e(TAG, "Key draw hook failed, drawing stock", t);
            }
        }
        ((KeyDrawable) drawable).patch_drawLayers(canvas);
    }

    static State stateOf(Drawable drawable, boolean create) {
        if (!(drawable instanceof KeyDrawable)) return null;
        KeyDrawable key = (KeyDrawable) drawable;
        Object state = key.patch_getKeyDrawState();
        if (state instanceof State) return (State) state;
        if (!create) return null;

        State created = new State();
        key.patch_setKeyDrawState(created);
        return created;
    }

    /**
     * The visible background of a key drawn in its last draw, in that draw's coordinates.
     *
     * @return false if the key has not drawn, or its theme background paints nothing.
     */
    static boolean drawnBackground(State state, RectF outRect, float[] outRadii, float fallbackRadius) {
        if (!state.drawn || state.backgroundDrawable == null || state.background.isEmpty()) return false;

        Rect background = state.background;
        Shape shape = state.drawnShape();
        outRect.set(background.left + shape.left, background.top + shape.top,
                background.right - shape.right, background.bottom - shape.bottom);
        fillRadii(shape, outRect, outRadii, fallbackRadius);
        return shape.visible && outRect.width() >= 1f && outRect.height() >= 1f;
    }

    /**
     * Finds the visible background of a key in the caller's coordinates.
     *
     * @param keyDrawable        The key's drawable (from the key, for the current theme).
     * @param area               Where the key drawable's bounds lie (the key's full area).
     * @param fallbackBackground Where SwiftKey's layout puts the background layer, used if the key
     *                           has not drawn this drawable yet. May be null.
     * @return A mask of {@link #VISIBLE} and {@link #FROM_DRAW}. outRect is always set.
     */
    public static int visibleBackground(Drawable keyDrawable, RectF area, RectF fallbackBackground,
                                        float fallbackRadius, RectF outRect, float[] outRadii) {
        RectF background = fallbackBackground != null ? fallbackBackground : area;
        outRect.set(background);

        Drawable layer = null;
        if (keyDrawable instanceof LayerDrawable && ((LayerDrawable) keyDrawable).getNumberOfLayers() > 0) {
            layer = ((LayerDrawable) keyDrawable).getDrawable(0);
        }
        if (layer == null) {
            fillRadii(null, outRect, outRadii, fallbackRadius);
            return VISIBLE;
        }

        int flags = 0;
        Shape shape;
        State state = stateOf(keyDrawable, false);
        if (state != null && state.drawn && state.backgroundDrawable == layer
                && !state.bounds.isEmpty() && !state.background.isEmpty()) {
            // The background's bounds as drawn, relative to the key drawable's.
            Rect bounds = state.bounds;
            Rect drawn = state.background;
            outRect.set(area.left + (drawn.left - bounds.left), area.top + (drawn.top - bounds.top),
                    area.right - (bounds.right - drawn.right), area.bottom - (bounds.bottom - drawn.bottom));
            shape = state.drawnShape();
            flags |= FROM_DRAW;
        } else {
            shape = shapeOf(layer, Math.round(outRect.width()), Math.round(outRect.height()));
        }

        outRect.set(outRect.left + shape.left, outRect.top + shape.top,
                outRect.right - shape.right, outRect.bottom - shape.bottom);
        fillRadii(shape, outRect, outRadii, fallbackRadius);
        if (shape.visible && outRect.width() >= 1f && outRect.height() >= 1f) flags |= VISIBLE;
        return flags;
    }

    private static void fillRadii(Shape shape, RectF rect, float[] out, float fallbackRadius) {
        float max = Math.max(0f, Math.min(rect.width(), rect.height()) / 2f);
        if (shape == null || shape.radii == null) {
            Arrays.fill(out, Math.min(fallbackRadius, max));
            return;
        }
        for (int i = 0; i < 8; i++) out[i] = Math.max(0f, Math.min(shape.radii[i], max));
    }

    // region Measuring

    private static final class ShapeKey {
        Drawable drawable;
        int width, height;

        ShapeKey set(Drawable drawable, int width, int height) {
            this.drawable = drawable;
            this.width = width;
            this.height = height;
            return this;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ShapeKey)) return false;
            ShapeKey other = (ShapeKey) o;
            return drawable == other.drawable && width == other.width && height == other.height;
        }

        @Override
        public int hashCode() {
            return (System.identityHashCode(drawable) * 31 + width) * 31 + height;
        }
    }

    // Theme drawables are shared by every key of a style, so a few entries cover a keyboard.
    private static final HashMap<ShapeKey, Shape> shapes = new HashMap<>();
    private static final ShapeKey lookup = new ShapeKey();

    static Shape shapeOf(Drawable drawable, int width, int height) {
        if (drawable == null || width <= 0 || height <= 0) return Shape.INVISIBLE;

        Shape shape = shapes.get(lookup.set(drawable, width, height));
        lookup.drawable = null;
        if (shape != null) return shape;

        try {
            shape = measure(drawable, width, height, 0);
        } catch (Throwable t) {
            Log.e(TAG, "Could not measure a key background", t);
            shape = new Shape(true, 0, 0, 0, 0, null);
        }

        if (shapes.size() > 256) shapes.clear();
        shapes.put(new ShapeKey().set(drawable, width, height), shape);
        return shape;
    }

    private static Shape measure(Drawable drawable, int width, int height, int depth) {
        if (drawable == null || width <= 0 || height <= 0) return Shape.INVISIBLE;
        if (depth > 6) return new Shape(true, 0, 0, 0, 0, null);

        if (drawable instanceof GradientDrawable) {
            return new Shape(true, 0, 0, 0, 0, shapeRadii((GradientDrawable) drawable, width, height));
        }

        if (drawable instanceof ColorDrawable) {
            return ((ColorDrawable) drawable).getAlpha() == 0
                    ? Shape.INVISIBLE
                    : new Shape(true, 0, 0, 0, 0, new float[8]);
        }

        // StateListDrawable and friends: the state currently shown.
        if (drawable instanceof DrawableContainer) {
            Drawable current = drawable.getCurrent();
            if (current == null) return Shape.INVISIBLE;
            if (current != drawable) return measure(current, width, height, depth + 1);
        }

        if (drawable instanceof LayerDrawable) {
            Shape shape = measureLayers((LayerDrawable) drawable, width, height, depth);
            if (shape != null) return shape;
        }

        // 9-patches, bitmaps, wrappers whose insets aren't readable: look at the pixels.
        return raster(drawable, width, height);
    }

    /** Null when the layers are laid out in a way this doesn't model. */
    private static Shape measureLayers(LayerDrawable layers, int width, int height, int depth) {
        int count = layers.getNumberOfLayers();
        if (count == 0) return Shape.INVISIBLE;

        Rect padding = new Rect();
        float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = Float.MAX_VALUE, bottom = Float.MAX_VALUE;
        float[] radii = null;
        float largest = -1f;
        boolean visible = false;

        for (int i = 0; i < count; i++) {
            if (layers.getLayerGravity(i) != Gravity.NO_GRAVITY
                    || layers.getLayerWidth(i) >= 0 || layers.getLayerHeight(i) >= 0) {
                return null;
            }
            // Layers nest inside the padding of the layers before them.
            if (i > 0) {
                Drawable previous = layers.getDrawable(i - 1);
                if (previous != null && previous.getPadding(padding)
                        && (padding.left | padding.top | padding.right | padding.bottom) != 0) {
                    return null;
                }
            }

            int insetLeft = layers.getLayerInsetLeft(i);
            int insetRight = layers.getLayerInsetRight(i);
            int start = layers.getLayerInsetStart(i);
            int end = layers.getLayerInsetEnd(i);
            // Keys are laid out left to right; INSET_UNDEFINED is Integer.MIN_VALUE.
            if (start != Integer.MIN_VALUE) insetLeft = start;
            if (end != Integer.MIN_VALUE) insetRight = end;
            int insetTop = layers.getLayerInsetTop(i);
            int insetBottom = layers.getLayerInsetBottom(i);

            int layerWidth = width - insetLeft - insetRight;
            int layerHeight = height - insetTop - insetBottom;
            Shape child = measure(layers.getDrawable(i), layerWidth, layerHeight, depth + 1);
            if (!child.visible) continue;

            float childLeft = insetLeft + child.left;
            float childTop = insetTop + child.top;
            float childRight = insetRight + child.right;
            float childBottom = insetBottom + child.bottom;
            left = Math.min(left, childLeft);
            top = Math.min(top, childTop);
            right = Math.min(right, childRight);
            bottom = Math.min(bottom, childBottom);
            visible = true;

            // The biggest layer gives the outline its corners.
            float area = (width - childLeft - childRight) * (height - childTop - childBottom);
            if (area > largest) {
                largest = area;
                radii = child.radii;
            }
        }

        return visible ? new Shape(true, left, top, right, bottom, radii) : Shape.INVISIBLE;
    }

    private static float[] shapeRadii(GradientDrawable shape, int width, int height) {
        float max = Math.min(width, height) / 2f;
        float[] out = new float[8];
        if (shape.getShape() == GradientDrawable.OVAL) {
            Arrays.fill(out, max);
            return out;
        }
        if (shape.getShape() != GradientDrawable.RECTANGLE) return null;

        // Null unless the theme set per-corner radii.
        float[] radii = shape.getCornerRadii();
        if (radii != null && radii.length == 8) {
            for (int i = 0; i < 8; i++) out[i] = Math.max(0f, Math.min(radii[i], max));
        } else {
            Arrays.fill(out, Math.max(0f, Math.min(shape.getCornerRadius(), max)));
        }
        return out;
    }

    /** Draws the drawable into an alpha mask and finds its mostly opaque box and corner rounding. */
    private static Shape raster(Drawable drawable, int width, int height) {
        float scale = 1f;
        long pixels = (long) width * height;
        if (pixels > MAX_RASTER_PIXELS) scale = (float) Math.sqrt(MAX_RASTER_PIXELS / (double) pixels);
        int maskWidth = Math.max(1, Math.round(width * scale));
        int maskHeight = Math.max(1, Math.round(height * scale));

        Bitmap mask = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ALPHA_8);
        float scaleX = maskWidth / (float) width;
        float scaleY = maskHeight / (float) height;
        Canvas canvas = new Canvas(mask);
        canvas.scale(scaleX, scaleY);

        // Shared drawable: borrow it at this size and put its bounds back.
        Rect saved = drawable.copyBounds();
        try {
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
        } finally {
            drawable.setBounds(saved);
        }

        int rowBytes = mask.getRowBytes();
        byte[] alpha = new byte[rowBytes * maskHeight];
        mask.copyPixelsToBuffer(ByteBuffer.wrap(alpha));
        mask.recycle();

        int max = 0;
        for (byte a : alpha) max = Math.max(max, a & 0xFF);
        if (max == 0) return Shape.INVISIBLE;
        // The visible edge is where it's at least half as opaque as the body. Below that is shadow.
        int threshold = Math.max(1, (max + 1) / 2);

        int minX = maskWidth, minY = maskHeight, maxX = -1, maxY = -1;
        for (int y = 0; y < maskHeight; y++) {
            int row = y * rowBytes;
            for (int x = 0; x < maskWidth; x++) {
                if ((alpha[row + x] & 0xFF) < threshold) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) return Shape.INVISIBLE;

        // Walk in diagonally from each corner of the box to the edge. A corner of radius r
        // meets the diagonal r * (1 - 1/sqrt 2) in on each axis.
        int reach = Math.min(maxX - minX, maxY - minY) / 2;
        float[] radii = new float[8];
        int[][] corners = {{minX, minY, 1, 1}, {maxX, minY, -1, 1}, {maxX, maxY, -1, -1}, {minX, maxY, 1, -1}};
        for (int c = 0; c < 4; c++) {
            int x = corners[c][0], y = corners[c][1];
            int step = 0;
            while (step < reach && (alpha[(y + step * corners[c][3]) * rowBytes + x + step * corners[c][2]] & 0xFF) < threshold) {
                step++;
            }
            float radius = step / (1f - 0.70710678f) / ((scaleX + scaleY) / 2f);
            radii[c * 2] = radius;
            radii[c * 2 + 1] = radius;
        }

        return new Shape(true, minX / scaleX, minY / scaleY,
                width - (maxX + 1) / scaleX, height - (maxY + 1) / scaleY, radii);
    }

    // endregion
}
