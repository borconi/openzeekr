package io.aawireless.wirelessaamanipulator.helpers.energymodel;

import android.content.Context;

import io.aawireless.wirelessaamanipulator.R;

/**
 * Registry of selectable EV energy profiles (res/raw JSON). The user picks one in
 * settings ("energy_profile" in AA_SETTINGS); {@link EnergyModelTranslator} loads it.
 *
 * Covers the EU lineup, grouped by family: 001, 7GT, 7X, 009, X. The 7GT IS the 007 GT
 * (the 007 sedan is China-only), so there are no separate 007 profiles; the EU 7GT ships as
 * 100 kWh AWD, 100 kWh RWD and 75 kWh RWD (there is no 95 kWh AWD).
 *
 * Each profile was derived from one reference car's tables using the model's real published
 * specs where available (kerb mass, usable battery kWh, combined peak power, drive layout) and
 * sensible estimates for Cd * frontal area. Road-load constant/linear are mass-scaled from the
 * reference; quadratic is scaled by Cd * frontal area; the drivetrain-loss curve is re-sampled
 * to each car's peak motor power keeping the reference loss fraction. Several kerb masses and
 * all frontal-area figures are estimated, so the aero term is approximate - refine with real
 * telemetry where possible.
 */
public final class EnergyProfiles {

    public static final String PREF_KEY = "energy_profile";
    public static final String DEFAULT_KEY = "zeekr_7gt";

    public static final class Profile {
        public final String key;
        public final String name;
        public final int rawResId;
        Profile(String key, String name, int rawResId) {
            this.key = key; this.name = name; this.rawResId = rawResId;
        }
    }

    public static final Profile[] ALL = {
            // ---- Zeekr 001 ----
            new Profile("zeekr_001_rwd_100", "Zeekr 001 RWD (100 kWh)", R.raw.zeekr_001_rwd_100),
            new Profile("zeekr_001_awd_100", "Zeekr 001 AWD (100 kWh)", R.raw.zeekr_001_awd_100),
            new Profile("zeekr_001fr",       "Zeekr 001 FR (100 kWh)",  R.raw.zeekr_001fr),
            // ---- Zeekr 7GT (== 007 GT) ----
            new Profile("zeekr_7gt",    "Zeekr 7GT AWD (100 kWh)", R.raw.zeekr_7gt),
            new Profile("zeekr_7gt_lr", "Zeekr 7GT RWD (100 kWh)", R.raw.zeekr_7gt_lr),
            new Profile("zeekr_7gt_75", "Zeekr 7GT RWD (75 kWh)",  R.raw.zeekr_7gt_75),
            // ---- Zeekr 7X ----
            new Profile("zeekr_7x",     "Zeekr 7X (100 kWh)",      R.raw.zeekr_7x),
            // ---- Zeekr 009 ----
            new Profile("zeekr_009_116", "Zeekr 009 (116 kWh)",    R.raw.zeekr_009_116),
            new Profile("zeekr_009_140", "Zeekr 009 (140 kWh)",    R.raw.zeekr_009_140),
            // ---- Zeekr X ----
            new Profile("zeekr_x",      "Zeekr X (66 kWh)",        R.raw.zeekr_x),
    };

    private EnergyProfiles() {}

    public static Profile byKey(String key) {
        for (Profile p : ALL) if (p.key.equals(key)) return p;
        return byKeyDefault();
    }

    public static Profile byKeyDefault() {
        for (Profile p : ALL) if (p.key.equals(DEFAULT_KEY)) return p;
        return ALL[0];
    }

    /** The raw resource id for the currently-selected profile. */
    public static int selectedRawRes(Context ctx) {
        String key = ctx.getSharedPreferences("AA_SETTINGS", Context.MODE_PRIVATE)
                .getString(PREF_KEY, DEFAULT_KEY);
        return byKey(key).rawResId;
    }

    public static String selectedKey(Context ctx) {
        return ctx.getSharedPreferences("AA_SETTINGS", Context.MODE_PRIVATE)
                .getString(PREF_KEY, DEFAULT_KEY);
    }
}
