package app.revanced.extension.swiftkey.toolbar;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.widget.ImageView;

/**
 * The mods' toolbar icons, drawn in code so the patches need no resources. A toolbar item's icon
 * is a resource id, so the mods' items return the ids below (no package uses 0x7e), and the patch
 * routes every place SwiftKey turns an item's icon id into a Drawable through {@link #getDrawable}
 * or {@link #setImageResource}. Each icon is a white 24dp shape on a 24x24 grid, like the stock
 * vector icons, so SwiftKey's tints and color filters apply as they do to its own.
 */
@SuppressWarnings("unused")
public final class ToolbarIcons {
    public static final int TWO_THUMB_ON = 0x7e000001;
    public static final int TWO_THUMB_OFF = 0x7e000002;
    public static final int CAPITAL_I_ON = 0x7e000003;
    public static final int CAPITAL_I_OFF = 0x7e000004;

    private ToolbarIcons() {
    }

    /** Injected in place of Context.getDrawable(id) where SwiftKey loads a toolbar item's icon. */
    public static Drawable getDrawable(Context context, int id) {
        Path path = path(id);
        return path != null ? drawable(context.getResources(), path) : context.getDrawable(id);
    }

    /** Injected in place of ImageView.setImageResource(id) where SwiftKey shows a toolbar item's icon. */
    public static void setImageResource(ImageView view, int id) {
        Path path = path(id);
        if (path != null) view.setImageDrawable(drawable(view.getResources(), path));
        else view.setImageResource(id);
    }

    private static Drawable drawable(Resources resources, Path path) {
        ShapeDrawable drawable = new ShapeDrawable(new PathShape(path, 24, 24));
        int size = Math.round(24 * resources.getDisplayMetrics().density);
        drawable.setIntrinsicWidth(size);
        drawable.setIntrinsicHeight(size);
        drawable.getPaint().setColor(Color.WHITE);
        drawable.getPaint().setAntiAlias(true);
        return drawable;
    }

    /** The icon's shape, or null when [id] isn't one of the mods' icons. */
    private static Path path(int id) {
        Path path = new Path();
        switch (id) {
            // Two thumbs: filled when two-thumb mode is on, outlined when off.
            case TWO_THUMB_ON:
                thumbs(path, 4.5f, 5.5f);
                return path;
            case TWO_THUMB_OFF:
                thumbs(path, 4.5f, 5.5f);
                thumbs(path, 2.5f, 3.5f);
                path.setFillType(Path.FillType.EVEN_ODD);
                return path;
            // What typing "i" gives: a serif capital I when on, a lowercase i when off.
            case CAPITAL_I_ON:
                polygon(path, 7, 4, 17, 4, 17, 6, 13, 6, 13, 18, 17, 18, 17, 20, 7, 20, 7, 18, 11, 18, 11, 6, 7, 6);
                return path;
            case CAPITAL_I_OFF:
                path.addCircle(12, 5.5f, 1.75f, Path.Direction.CW);
                polygon(path, 9.5f, 9.5f, 13, 9.5f, 13, 18, 15, 18, 15, 20, 9, 20, 9, 18, 11, 18, 11, 11.5f, 9.5f, 11.5f);
                return path;
            default:
                return null;
        }
    }

    /** Both thumbs as ellipses with radii [rx], [ry], centred at (7, 13) and (17, 11). */
    private static void thumbs(Path path, float rx, float ry) {
        path.addOval(new RectF(7 - rx, 13 - ry, 7 + rx, 13 + ry), Path.Direction.CW);
        path.addOval(new RectF(17 - rx, 11 - ry, 17 + rx, 11 + ry), Path.Direction.CW);
    }

    private static void polygon(Path path, float... points) {
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        path.close();
    }
}
