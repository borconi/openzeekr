package io.aawireless.wirelessaamanipulator.helpers.ssl;

/**
 * Verbatim port of the aa-cert-extractor obfuscation routine.
 * Pure byte math - identical behaviour on desktop JVM and Android.
 * DO NOT "clean up" the arithmetic; it must match the gearhead scheme exactly.
 */
public final class ObfuscationUtils {

    private ObfuscationUtils() {}

    public static void deobfuscate(byte[] var0, byte[] var1, byte[] var2) {
        for (int var3 = 0; var3 < var0.length; ++var3) {
            for (int var4 = 0; var4 < 48; ++var4) {
                int var5 = var1[var4] & 255;
                var1[var4] = (byte) ((var5 >> 7 | var5 + var5) + 33 ^ var2[var4 % var2.length] ^ var0[var3]);
            }
        }
    }
}
