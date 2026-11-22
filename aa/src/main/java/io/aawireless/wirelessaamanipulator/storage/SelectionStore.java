package io.aawireless.wirelessaamanipulator.storage;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which Bluetooth device is allowed to auto-start the app.
 *
 * Two modes:
 *   - ANY (selected address == null/empty): the app fires for ANY connected BT
 *     device that advertises the Android Auto profile UUID (the default).
 *   - SPECIFIC (selected address set): the app fires ONLY when that exact device
 *     connects, regardless of advertised UUIDs.
 */
public final class SelectionStore {
    private static final String PREFS = "aa_device_selection";
    private static final String KEY_ADDRESS = "selected_address";

    private SelectionStore() {}

    /** @param address a BT MAC, or null/empty to mean "any AA device". */
    public static void setSelectedAddress(Context ctx, String address) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (address == null || address.trim().isEmpty()) {
            sp.edit().remove(KEY_ADDRESS).apply();
        } else {
            sp.edit().putString(KEY_ADDRESS, address.trim()).apply();
        }
    }

    /** @return the selected MAC, or null when in "any AA device" mode. */
    public static String getSelectedAddress(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String a = sp.getString(KEY_ADDRESS, null);
        return (a == null || a.trim().isEmpty()) ? null : a.trim();
    }

    /** @return true when no specific device is pinned (fire for any AA device). */
    public static boolean isAnyMode(Context ctx) {
        return getSelectedAddress(ctx) == null;
    }

    public static boolean matches(Context ctx, String address) {
        String sel = getSelectedAddress(ctx);
        return sel != null && address != null && sel.equalsIgnoreCase(address.trim());
    }
}
