package io.aawireless.wirelessaamanipulator.storage;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class KnowDevicesStore {
    private static final String PREFS = "known_bt_devices";
    private static final String KEY_SET = "addresses";

    private KnowDevicesStore() {}

    public static void add(Context ctx, String address) {
        if (address == null || address.isEmpty()) return;

        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> cur = sp.getStringSet(KEY_SET, Collections.emptySet());
        Set<String> copy = new HashSet<>(cur);
        if (copy.add(address)) {
            sp.edit().putStringSet(KEY_SET, copy).apply();
        }
    }

    public static boolean contains(Context ctx, String address) {
        if (address == null || address.isEmpty()) return false;

        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> cur = sp.getStringSet(KEY_SET, Collections.emptySet());
        return cur != null && cur.contains(address);
    }

    public static Set<String> getAll(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> cur = sp.getStringSet(KEY_SET, Collections.emptySet());
        return cur == null ? Collections.emptySet() : new HashSet<>(cur);
    }
}
