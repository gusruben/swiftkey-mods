package app.revanced.extension.swiftkey.toolbar;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import app.revanced.extension.swiftkey.twothumb.TwoThumbMode;
import app.revanced.extension.swiftkey.typing.CapitalIPatch;

/**
 * The mods' toolbar buttons. Each patch appends its id to SwiftKey's list of known toolbar items
 * ({@code add*}, injected in that list's static initializer), and one hook in the toolbar item
 * factory asks {@link #create} for every id SwiftKey has no case for.
 */
@SuppressWarnings("unused")
public final class ToolbarItems {
    private static final String TAG = "SwiftKeyMods";
    // Same telemetry slot as the autocorrect button; the item needs one.
    private static final String TELEMETRY_ITEM_NAME = "AUTOCORRECT";

    private static Constructor<?> itemConstructor;

    private ToolbarItems() {
    }

    private static List<Integer> add(List<Integer> items, int id) {
        List<Integer> list = new ArrayList<>(items);
        if (!list.contains(id)) list.add(id);
        return Collections.unmodifiableList(list);
    }

    public static List<Integer> addCapitalI(List<Integer> items) {
        return add(items, CapitalIPatch.TOOLBAR_ITEM_ID);
    }

    public static List<Integer> addTwoThumb(List<Integer> items) {
        return add(items, TwoThumbMode.TOOLBAR_ITEM_ID);
    }

    /**
     * Injected in the toolbar item factory before its "No toolbar item" throw.
     *
     * @return The item for [id], or null when it isn't one of ours (the factory then throws as stock).
     */
    public static Object create(Class<?> itemClass, int id) {
        if (id == CapitalIPatch.TOOLBAR_ITEM_ID) return CapitalIPatch.createToolbarItem(itemClass);
        if (id == TwoThumbMode.TOOLBAR_ITEM_ID) return TwoThumbMode.createToolbarItem(itemClass);
        return null;
    }

    /**
     * Builds an on/off button with the toolbar's toggle item class (the one autocorrect and
     * incognito use): id, telemetry enum, icon, descriptions, caption, click, long click,
     * pre-click, observables, visible, actionable. Built reflectively, so no obfuscated names.
     */
    public static Object toggleItem(Class<?> itemClass, int id, Supplier<Integer> icon, Supplier<String> description,
                                    String caption, Runnable click) {
        try {
            if (itemConstructor == null) {
                for (Constructor<?> constructor : itemClass.getDeclaredConstructors()) {
                    if (constructor.getParameterTypes().length == 12) itemConstructor = constructor;
                }
                if (itemConstructor == null) throw new IllegalStateException("No item constructor in " + itemClass);
                itemConstructor.setAccessible(true);
            }
            Class<?>[] types = itemConstructor.getParameterTypes();
            Supplier<String> captionSupplier = () -> caption;
            Supplier<Boolean> always = () -> Boolean.TRUE;
            return itemConstructor.newInstance(
                    id,
                    telemetryItem(types[1]),
                    icon,
                    description, // Description when actionable, which is always.
                    description,
                    captionSupplier, // Label in the expanded tool grid.
                    listener(types[6], click), // Click.
                    listener(types[7], null), // Long click: not handled.
                    listener(types[8], null), // Before either click.
                    Collections.emptyList(), // Observables; the button redraws itself after a click.
                    always, // Visible.
                    always // Actionable.
            );
        } catch (Exception ex) {
            Log.e(TAG, "Toolbar item " + id + " failed", ex);
            throw new RuntimeException(ex);
        }
    }

    /** Implements one of the toolbar's single-method listener interfaces. */
    private static Object listener(Class<?> type, Runnable action) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        return "ModsToolbarListener";
                }
            }
            if (action != null) action.run();
            // The long click listener returns whether it handled the click.
            return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
        };
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object telemetryItem(Class<?> type) {
        try {
            return Enum.valueOf((Class) type, TELEMETRY_ITEM_NAME);
        } catch (Exception ex) {
            return type.getEnumConstants()[0];
        }
    }
}
