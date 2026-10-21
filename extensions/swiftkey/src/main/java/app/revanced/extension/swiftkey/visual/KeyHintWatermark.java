package app.revanced.extension.swiftkey.visual;

import app.revanced.extension.swiftkey.settings.ModSettings;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;

import java.util.Arrays;

/**
 * Draws a key's long-press symbol (SwiftKey's "top text", e.g. q's 1 or a's @) as a large,
 * faint watermark behind the key's letter, cropped to the key's visible background.
 * <p>
 * The patch keeps every key's top text in the layout (as if "Long-press symbols" were on),
 * stops SwiftKey drawing it as a corner hint, and hands this class each key's top text as a
 * stock label drawable. The text, font and colour are read back by drawing that label (and the
 * key's main label) into a canvas that only records text, so nothing depends on obfuscated names.
 * <p>
 * Everything runs on the UI thread.
 */
@SuppressWarnings("unused")
public final class KeyHintWatermark {
    /** Added to the key content class that holds a top and a bottom label. */
    public interface HintContent {
        /** Renders the top label as a stock label drawable, in the main label's text style. */
        Drawable patch_buildHint(Object renderer, Object style);
    }

    private static final String TAG = "KeyHintWatermark";

    // Tuning. Offsets are fractions of the visible background; negative ones push the glyph
    // past that edge, where it's cropped.
    // Watermark size, as a multiple of the size SwiftKey draws the corner hint at.
    // Opacity, as a fraction of the main label's own.
    // Cap on the glyph's height, as a fraction of the background's height.
    // Cap on the glyph's ink height, in key heights. The clip crops anything past the key edge,
    // so this only stops a huge size setting from growing the glyph out of sight.
    private static final float MAX_HEIGHT = 3f;

    private static final boolean BOLD = true;

    // SwiftKey's split when it shows the hint: the main label gets the bottom 65% of the label
    // area and the hint is fitted into the rest. Used to find the stock hint size.
    private static final float STOCK_MAIN_FRACTION = 0.65f;

    private static boolean loggedError;

    // Set while a key's label is built, attached to the key drawable it ends up in.
    private static Drawable pendingHint;

    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final TextProbe probe = new TextProbe();
    private static final RectF visible = new RectF();
    private static final float[] radii = new float[8];

    /** Cached per key drawable. */
    private static final class Watermark {
        // From the labels, once.
        boolean read;
        String text;
        Typeface typeface;
        float hintSize;
        int color;

        // Laid out for one visible background.
        final RectF laidOutFor = new RectF();
        final float[] laidOutRadii = new float[8];
        final Path clip = new Path();
        final Rect ink = new Rect();
        float size, x, y;
        float laidOutSizeMult, laidOutOffsetX, laidOutOffsetY;
    }

    /**
     * Injected after SwiftKey reads whether the key's style shows top text.
     *
     * @return false, so the key draws only its main label, centred as usual.
     */
    public static boolean showStockTopText(boolean original) {
        return !ModSettings.watermarkHints() && original;
    }

    /** Injected at the start of the top-and-bottom content's render. */
    public static void onRenderHintContent(Object content, Object renderer, Object style) {
        if (!ModSettings.watermarkHints() || !(content instanceof HintContent)) return;
        try {
            pendingHint = ((HintContent) content).patch_buildHint(renderer, style);
        } catch (Throwable t) {
            logError("Could not build a key hint", t);
        }
    }

    /** Injected at the start of the key drawable factory. */
    public static void clearPendingHint() {
        pendingHint = null;
    }

    /** Injected before the key drawable factory returns. */
    public static void onKeyDrawableCreated(Drawable keyDrawable) {
        Drawable hint = pendingHint;
        pendingHint = null;
        if (hint == null) return;

        KeyDrawHook.State state = KeyDrawHook.stateOf(keyDrawable, true);
        if (state != null) state.hint = hint;
    }

    /**
     * Draws the key's layers with the watermark between the background and the label.
     *
     * @return false to let the key draw stock.
     */
    static boolean drawLayers(LayerDrawable drawable, Canvas canvas, KeyDrawHook.State state) {
        if (!ModSettings.watermarkHints()) return false;

        int layers = drawable.getNumberOfLayers();
        Drawable background = drawable.getDrawable(0);
        if (background != null) background.draw(canvas);

        try {
            drawWatermark(drawable, canvas, state);
        } catch (Throwable t) {
            state.hint = null;
            logError("Could not draw a key hint watermark", t);
        }

        for (int i = 1; i < layers; i++) {
            Drawable layer = drawable.getDrawable(i);
            if (layer != null) layer.draw(canvas);
        }
        return true;
    }

    private static void drawWatermark(LayerDrawable drawable, Canvas canvas, KeyDrawHook.State state) {
        Watermark watermark = state.watermark instanceof Watermark ? (Watermark) state.watermark : null;
        if (watermark == null) {
            watermark = new Watermark();
            state.watermark = watermark;
        }
        if (!watermark.read && !readLabels(drawable, state, watermark)) return;
        if (watermark.text == null) return;

        // An invisible background still has a letter on it: crop to the layer's bounds instead.
        if (!KeyDrawHook.drawnBackground(state, visible, radii, 0f)) {
            if (state.background.isEmpty()) return;
            visible.set(state.background);
            Arrays.fill(radii, 0f);
        }

        if (!visible.equals(watermark.laidOutFor) || !Arrays.equals(radii, watermark.laidOutRadii)
                || watermark.laidOutSizeMult != ModSettings.watermarkSize()
                || watermark.laidOutOffsetX != ModSettings.watermarkOffsetX()
                || watermark.laidOutOffsetY != ModSettings.watermarkOffsetY()) {
            layOut(watermark);
        }

        paint.setTypeface(watermark.typeface);
        paint.setTextSize(watermark.size);
        paint.setColor(watermark.color);
        paint.setAlpha(Math.round(Color.alpha(watermark.color) * ModSettings.watermarkOpacity()));

        int save = canvas.save();
        canvas.clipPath(watermark.clip);
        canvas.drawText(watermark.text, watermark.x, watermark.y, paint);
        canvas.restoreToCount(save);
    }

    /**
     * Reads the hint's text, font and size, and the main label's colour.
     *
     * @return false if the label hasn't been laid out yet.
     */
    private static boolean readLabels(LayerDrawable drawable, KeyDrawHook.State state, Watermark watermark) {
        Drawable label = drawable.getNumberOfLayers() > 1 ? drawable.getDrawable(1) : null;
        Rect area = label != null ? label.getBounds() : drawable.getBounds();
        if (area.isEmpty()) return false;
        watermark.read = true;

        // Lay the hint out where SwiftKey would put it, so its size is the stock hint size.
        Drawable hint = state.hint;
        hint.setBounds(area.left, area.top, area.right,
                area.bottom - Math.round(area.height() * STOCK_MAIN_FRACTION));
        probe.reset();
        hint.draw(probe);
        if (probe.text == null || probe.text.trim().isEmpty()) return true;

        watermark.text = probe.text;
        // Bold variant of the theme's own hint font; Android synthesises it if the font has none.
        watermark.typeface = BOLD ? Typeface.create(probe.paint.getTypeface(), Typeface.BOLD) : probe.paint.getTypeface();
        watermark.hintSize = probe.paint.getTextSize();
        int color = probe.paint.getColor();

        // The theme's colour for the letter itself. Icons draw no text and keep the hint's colour.
        if (label != null) {
            probe.reset();
            label.draw(probe);
            if (probe.text != null) color = probe.paint.getColor();
        }
        watermark.color = color;
        return true;
    }

    private static void layOut(Watermark watermark) {
        watermark.laidOutFor.set(visible);
        System.arraycopy(radii, 0, watermark.laidOutRadii, 0, 8);

        watermark.clip.reset();
        watermark.clip.addRoundRect(visible, radii, Path.Direction.CW);

        String text = watermark.text;
        watermark.laidOutSizeMult = ModSettings.watermarkSize();
        float size = watermark.hintSize * watermark.laidOutSizeMult;
        paint.setTypeface(watermark.typeface);
        paint.setTextSize(size);
        paint.getTextBounds(text, 0, text.length(), watermark.ink);

        float maxHeight = visible.height() * MAX_HEIGHT;
        if (watermark.ink.height() > maxHeight && watermark.ink.height() > 0) {
            size *= maxHeight / watermark.ink.height();
            paint.setTextSize(size);
            paint.getTextBounds(text, 0, text.length(), watermark.ink);
        }
        watermark.size = size;

        // Place the glyph's ink, not its advance box, so the offsets read the same for every symbol.
        // Offsets are fractions of the key, in from the top-right corner; negative pushes past the edge.
        watermark.laidOutOffsetX = ModSettings.watermarkOffsetX();
        watermark.laidOutOffsetY = ModSettings.watermarkOffsetY();
        watermark.x = visible.right - watermark.laidOutOffsetX * visible.width() - watermark.ink.right;
        watermark.y = visible.top + watermark.laidOutOffsetY * visible.height() - watermark.ink.top;
    }

    private static void logError(String message, Throwable t) {
        if (loggedError) return;
        loggedError = true;
        Log.e(TAG, message, t);
    }

    /** A canvas that draws nothing and remembers the first text drawn on it, with its paint. */
    private static final class TextProbe extends Canvas {
        final Paint paint = new Paint();
        String text;

        void reset() {
            text = null;
        }

        private void capture(CharSequence drawn, Paint drawnWith) {
            if (text != null || drawn == null || drawn.length() == 0) return;
            text = drawn.toString();
            paint.set(drawnWith);
        }

        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            capture(text, paint);
        }

        @Override
        public void drawText(String text, int start, int end, float x, float y, Paint paint) {
            capture(text.substring(start, end), paint);
        }

        @Override
        public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
            capture(new String(text, index, count), paint);
        }

        @Override
        public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
            capture(text.subSequence(start, end), paint);
        }

        @Override
        public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
                                float x, float y, boolean isRtl, Paint paint) {
            capture(new String(text, index, count), paint);
        }

        @Override
        public void drawTextRun(CharSequence text, int start, int end, int contextStart, int contextEnd,
                                float x, float y, boolean isRtl, Paint paint) {
            capture(text.subSequence(start, end), paint);
        }
    }
}
