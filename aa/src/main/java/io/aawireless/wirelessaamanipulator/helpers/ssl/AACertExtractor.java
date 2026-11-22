package io.aawireless.wirelessaamanipulator.helpers.ssl;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import dalvik.system.DexClassLoader;

/**
 * On-device port of aa-cert-extractor. The desktop tool downloads the Android Auto
 * (gearhead) APK from Google Play and uses jadx to statically pull the cert PEM plus
 * two obfuscated byte[] blobs (encrypted RSA key + salt) from an obfuscated class,
 * then decrypts the key. jadx / the Play API cannot run on Android, so here we:
 *
 *   1. locate the ALREADY-INSTALLED gearhead APK via PackageManager,
 *   2. parse its dex headers ourselves (no restricted DexFile API) to shortlist the
 *      class(es) that declare >= 2 static byte[] fields,
 *   3. load those with a DexClassLoader and read the values by reflection - which is
 *      encoding-agnostic, so we don't care how the arrays were compiled,
 *   4. run the SAME decrypt routine (ObfuscationUtils + AES/CBC), which is pure Java.
 *
 * The heavy work runs once; {@link CertProvider} caches the resulting KeyStore.
 */
public final class AACertExtractor {

    private static final String TAG = "AACertExtractor";

    public static final String GEARHEAD_PKG = "com.google.android.projection.gearhead";

    /** Verbatim from aa-cert-extractor - part of the key-derivation input, must not change. */
    private static final String ROOT_CERT =
            "-----BEGIN CERTIFICATE-----\nMIIDiTCCAnGgAwIBAgIJAMFO56WkVE1CMA0GCSqGSIb3DQEBBQUAMFsxCzAJBgNV\nBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1Nb3VudGFpbiBW\naWV3MR8wHQYDVQQKDBZHb29nbGUgQXV0b21vdGl2ZSBMaW5rMB4XDTE0MDYwNjE4\nMjgxOVoXDTQ0MDYwNTE4MjgxOVowWzELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNh\nbGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxHzAdBgNVBAoMFkdvb2ds\nZSBBdXRvbW90aXZlIExpbmswggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIB\nAQDUH+iIbwwVb74NdI5eBv/ACFmh4ml/NOW7gUVWdYX50n8uQQsHHLCNIhk5VV2H\nhanvAZ/XXHPuVAPadE2HpnNqePKF/RDo4eJo/+rOief8gBYq/Z+OQTZeLdNm+GoI\nHBrEjU4Ms8IdLuFW0jF8LlIRgekjLHpc7duUl3QpwBlmAWQK40T/SZjprlmhyqfJ\ng1rxFdnGbrSibmCsTmb3m6WZyZUyrcwmd7t6q3pHbMABO+o02asPG/YPj/SJo4+i\nfb5/Nk56f3hH9pBiPKQXJnVUdVLKMXSRgydDBsGSBol4C0JL77MNDrMR5jdafJ4j\nmWmsa2+mnzoAv9AxEL9T0LiNAgMBAAGjUDBOMB0GA1UdDgQWBBS5dqvv8DPQiwrM\nfgn8xKR91k7wgjAfBgNVHSMEGDAWgBS5dqvv8DPQiwrMfgn8xKR91k7wgjAMBgNV\nHRMEBTADAQH/MA0GCSqGSIb3DQEBBQUAA4IBAQDKcnBsrbB0Jbz2VGJKP2lwYB6P\ndCTCCpQu7dVp61UQOX+zWfd2hnNMnLs/r1xPO+eyN0vmw7sD05phaIhbXVauKWZi\n9WqWHTaR+9s6CTyBOc1Mye0DMj+4vHt+WLmf0lYjkYUVYvR1EImX8ktXzkVmOqn+\ne30siqlZ8pQpsOgegIKfJ+pNQM8c3eXVv3KFMUgjZW33SziZL8IMsLvSO+1LtH37\nKqbTEMP6XUwVuZopgGvaHU74eT/WSRGlL7vX4OL5/UXXP4qsGH2Zp7uQlErv4H9j\nkMs37UL1vGb4M8RM7Eyu9/RulepSmqZUF+3i+3eby8iGq/3OWk9wgJf7AXnx\n-----END CERTIFICATE-----\n";

    private static final String CERT_MARKER = "-----BEGIN CERTIFICATE-----";

    /** KeyStore alias + key password used by sslEngineBuilder (kept "aa" for compatibility). */
    public static final String ALIAS = "aa";
    public static final char[] PASSWORD = "aa".toCharArray();

    public static final class Result {
        public final KeyStore keyStore;
        public final long expirationEpochMillis;
        public final long gearheadVersionCode;

        Result(KeyStore keyStore, long expirationEpochMillis, long gearheadVersionCode) {
            this.keyStore = keyStore;
            this.expirationEpochMillis = expirationEpochMillis;
            this.gearheadVersionCode = gearheadVersionCode;
        }
    }

    /** @return the installed gearhead versionCode, or -1 if AA isn't installed. */
    public static long getGearheadVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(GEARHEAD_PKG, 0).getLongVersionCode();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Extract the AA client cert + private key from the installed gearhead APK and
     * return a ready-to-use PKCS12 KeyStore (alias "aa", password "aa").
     *
     * @throws Exception if AA is not installed or the cert class can't be located.
     */
    public static Result extract(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        ApplicationInfo ai = pm.getApplicationInfo(GEARHEAD_PKG, 0);
        long versionCode = pm.getPackageInfo(GEARHEAD_PKG, 0).getLongVersionCode();

        List<String> apks = new ArrayList<>();
        if (ai.sourceDir != null) apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) {
            for (String s : ai.splitSourceDirs) apks.add(s);
        }
        Log.d(TAG, "gearhead v" + versionCode + " apks=" + apks);
        return extractFromApks(ctx, apks, versionCode);
    }

    /** versionCode of an APK sitting on disk (e.g. a downloaded one), or -1. */
    public static long getArchiveVersionCode(Context ctx, String apkPath) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apkPath, 0);
            return pi != null ? pi.getLongVersionCode() : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Extract the AA client cert from an arbitrary set of gearhead APK files (the
     * installed ones, or a freshly downloaded latest build). Same two-pass logic.
     */
    public static Result extractFromApks(Context ctx, List<String> apks, long versionCode) throws Exception {
        // --- Pass 1: find the LEAF cert PEM directly in the dex string pool, across
        // ALL apks/dexes. This is independent of class loading and of whether the
        // cert lives in the same class as the key/salt (newer gearhead builds split
        // them), so it's far more robust than requiring a class that has both. ---
        String certPem = null;
        for (String apkPath : apks) {
            certPem = scanApkForLeafCert(apkPath);
            if (certPem != null) {
                Log.d(TAG, "Leaf cert PEM found in string pool of " + apkPath
                        + " (len=" + certPem.length() + ")");
                break;
            }
        }
        if (certPem == null) {
            Log.w(TAG, "No leaf cert PEM in any dex string pool - gearhead may now store "
                    + "the cert outside a plain string constant.");
        }

        // --- Pass 2: brute-force the encrypted key + salt across every class with
        // >= 2 static byte[] fields, in every apk. ---
        int totalCandidates = 0, totalClinitFails = 0, totalDecryptFails = 0;
        Diag diag = new Diag();
        for (String apkPath : apks) {
            try {
                Result r = extractFromApk(ctx, apkPath, versionCode, certPem, diag);
                if (r != null) return r;
            } catch (Exception e) {
                Log.w(TAG, "extract from " + apkPath + " failed: " + e.getMessage());
            }
        }
        totalCandidates = diag.candidates;
        totalClinitFails = diag.clinitFails;
        totalDecryptFails = diag.decryptFails;

        // Rich failure message - surfaced in the UI status card so we know WHICH stage
        // broke on this gearhead build, not just "not found".
        throw new IllegalStateException("AA cert not extracted (gearhead v" + versionCode + "): "
                + "certPemFound=" + (certPem != null)
                + ", candidateKeyClasses=" + totalCandidates
                + ", clinitFailures=" + totalClinitFails
                + ", decryptFailures=" + totalDecryptFails
                + (certPem == null
                    ? " - cert string missing: needs jadx on the new APK to locate it"
                    : (totalCandidates == 0
                        ? " - no key/salt candidate classes: heuristic (>=2 static byte[]) no longer matches"
                        : " - cert present but no candidate's blobs decrypted to an RSA key")));
    }

    /** Accumulates diagnostics across apks so the final error can explain the failure. */
    private static final class Diag {
        int candidates, clinitFails, decryptFails;
    }

    private static Result extractFromApk(Context ctx, String apkPath, long versionCode,
                                         String certPemFromPool, Diag diag) throws Exception {
        // Shortlist classes that declare >= 2 static byte[] fields (the key+salt holder does).
        List<String> candidates = new ArrayList<>();
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String name = ze.getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    byte[] dex = readAll(zip.getInputStream(ze));
                    DexScan.collectCandidates(dex, candidates);
                }
            }
        }
        Log.d(TAG, apkPath + ": candidate classes with >=2 static byte[] fields: " + candidates.size());
        diag.candidates += candidates.size();
        if (candidates.isEmpty()) return null;

        String optimized = ctx.getCodeCacheDir().getAbsolutePath();
        // Parent MUST be the framework (boot) classloader, NOT this app's classloader. Gearhead
        // bundles its own Compose/Kotlin. If the parent is our app classloader, a gearhead class's
        // <clinit> resolves androidx.compose types up to OUR Compose runtime first, and the verifier
        // rejects the class (our ParcelableSnapshotMutableState vs gearhead's own hierarchy) with a
        // VerifyError - so the classes that hold the real key blobs never initialize and extraction
        // fails. An isolated (boot) parent makes gearhead resolve its OWN bundled classes from the
        // APK, which is what the standalone (non-Compose) app got for free. OpenZeekr is a Compose
        // app, so folding this in surfaced the collision; the boot parent removes it.
        ClassLoader frameworkParent = Object.class.getClassLoader();
        DexClassLoader loader = new DexClassLoader(apkPath, optimized, null, frameworkParent);

        for (String className : candidates) {
            try {
                Class<?> cls = Class.forName(className, false, loader);

                // Prefer the pool-scanned PEM; fall back to a same-class string source.
                String certPem = certPemFromPool != null ? certPemFromPool : readCertString(cls);
                if (certPem == null || !certPem.contains(CERT_MARKER)) continue;

                List<byte[]> blobs = readStaticByteArrays(cls); // may trigger <clinit>
                if (blobs.size() < 2) continue;

                PrivateKey pk = decryptAnyOrdering(certPem, blobs);
                if (pk == null) {
                    diag.decryptFails++;
                    Log.v(TAG, "candidate " + className + ": blobs present but decrypt failed");
                    continue;
                }

                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate leaf = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
                X509Certificate root = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(ROOT_CERT.getBytes(StandardCharsets.UTF_8)));

                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(null, null);
                ks.setKeyEntry(ALIAS, pk, PASSWORD, new Certificate[]{leaf, root});

                long expiry = leaf.getNotAfter() != null ? leaf.getNotAfter().getTime() : Long.MAX_VALUE;
                Log.i(TAG, "Extracted AA cert: key class=" + className
                        + " subject=" + leaf.getSubjectDN() + " notAfter=" + leaf.getNotAfter());
                return new Result(ks, expiry, versionCode);
            } catch (Throwable t) {
                if (t instanceof ExceptionInInitializerError
                        || t.getCause() instanceof ExceptionInInitializerError) {
                    diag.clinitFails++;
                }
                Log.v(TAG, "candidate " + className + " skipped: " + t);
            }
        }
        return null;
    }

    /** Scan every classes*.dex in the apk's string pool for the leaf cert PEM. */
    private static String scanApkForLeafCert(String apkPath) throws Exception {
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String name = ze.getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) {
                    String pem = DexScan.findLeafCertPem(readAll(zip.getInputStream(ze)));
                    if (pem != null) return pem;
                }
            }
        }
        return null;
    }

    /** Invoke the class's static, no-arg, String-returning method that yields the cert PEM. */
    private static String readCertString(Class<?> cls) {
        // Prefer a static String field if present.
        for (Field f : cls.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof String && ((String) v).contains(CERT_MARKER)) return (String) v;
                } catch (Throwable ignored) {}
            }
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getParameterTypes().length == 0
                    && m.getReturnType() == String.class) {
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(null);
                    if (v instanceof String && ((String) v).contains(CERT_MARKER)) return (String) v;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static List<byte[]> readStaticByteArrays(Class<?> cls) {
        List<byte[]> out = new ArrayList<>();
        for (Field f : cls.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == byte[].class) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v instanceof byte[] && ((byte[]) v).length > 0) out.add((byte[]) v);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    /**
     * The two blobs are the AES-encrypted key (length % 16 == 0) and the salt. Field
     * order isn't guaranteed by reflection, so try each (key, salt) pairing and accept
     * the one that yields a parseable RSA private key.
     */
    private static PrivateKey decryptAnyOrdering(String certPem, List<byte[]> blobs) {
        for (int i = 0; i < blobs.size(); i++) {
            byte[] key = blobs.get(i);
            if (key.length == 0 || key.length % 16 != 0) continue; // AES ciphertext only
            for (int j = 0; j < blobs.size(); j++) {
                if (i == j) continue;
                byte[] salt = blobs.get(j);
                try {
                    return decryptPrivateKey(certPem, key, salt);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** Verbatim port of AACertExtractor.decryptPrivateKey (Kotlin -> Java). */
    private static PrivateKey decryptPrivateKey(String cert, byte[] key, byte[] salt) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        byte[] bArr = new byte[48];
        java.nio.charset.Charset cs = StandardCharsets.UTF_8;
        ObfuscationUtils.deobfuscate(cert.getBytes(cs), bArr, salt);
        ObfuscationUtils.deobfuscate(ROOT_CERT.getBytes(cs), bArr, salt);
        for (int i = 0; i <= 6; i++) {
            ObfuscationUtils.deobfuscate(bArr, bArr, salt);
        }
        byte[] bArr2 = new byte[32];
        System.arraycopy(bArr, 0, bArr2, 0, 32);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(bArr2, "AES"), new IvParameterSpec(bArr, 32, 16));
        byte[] doFinal = cipher.doFinal(key);
        int length = doFinal.length - 54;
        byte[] bArr3 = new byte[length];
        System.arraycopy(doFinal, 28, bArr3, 0, length);
        // NO_WRAP == 2 (matches io.aawireless.utils.Base64.decode(bArr3, 2)).
        byte[] pkcs8 = Base64.decode(bArr3, Base64.NO_WRAP);
        return KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pkcs8));
    }

    private static byte[] readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------
    // Minimal DEX reader: enumerate classes that declare >= 2 static byte[]
    // fields. Reads the file bytes directly, so it avoids the restricted
    // dalvik.system.DexFile enumeration API and works on all API levels.
    // ------------------------------------------------------------------
    static final class DexScan {

        private static final int ACC_STATIC = 0x0008;

        /**
         * Find the LEAF cert PEM by scanning the raw dex for the BEGIN-CERTIFICATE
         * marker. PEM is pure ASCII, so a dex string constant holding it is stored
         * byte-for-byte and NUL-terminated (MUTF-8 == ASCII here). We read from the
         * marker to the terminating 0x00 to recover the exact constant, and skip the
         * one that equals our hard-coded ROOT_CERT (we want the client leaf).
         *
         * This is class-loader-independent, so it works even when the cert no longer
         * lives in a readable static String field.
         */
        static String findLeafCertPem(byte[] dex) {
            byte[] needle = CERT_MARKER.getBytes(StandardCharsets.US_ASCII);
            String rootTrim = ROOT_CERT.trim();
            for (int i = 0; i + needle.length < dex.length; i++) {
                if (!matchesAt(dex, i, needle)) continue;
                // Read from marker until NUL to recover the full constant.
                int end = i;
                while (end < dex.length && dex[end] != 0x00) end++;
                String s = new String(dex, i, end - i, StandardCharsets.US_ASCII);
                if (!s.contains("END CERTIFICATE")) continue;
                if (s.trim().equals(rootTrim)) continue; // that's the root, keep looking
                return s;
            }
            return null;
        }

        private static boolean matchesAt(byte[] hay, int off, byte[] needle) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[off + j] != needle[j]) return false;
            }
            return true;
        }

        static void collectCandidates(byte[] dex, List<String> out) {
            try {
                ByteBuffer bb = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);

                int stringIdsSize = bb.getInt(56);
                int stringIdsOff  = bb.getInt(60);
                int typeIdsSize   = bb.getInt(64);
                int typeIdsOff    = bb.getInt(68);
                int fieldIdsSize  = bb.getInt(80);
                int fieldIdsOff   = bb.getInt(84);
                int classDefsSize = bb.getInt(96);
                int classDefsOff  = bb.getInt(100);

                // string_ids -> data offsets
                int[] stringDataOff = new int[stringIdsSize];
                for (int i = 0; i < stringIdsSize; i++) stringDataOff[i] = bb.getInt(stringIdsOff + i * 4);

                // type_ids -> descriptor string index
                int[] typeDescIdx = new int[typeIdsSize];
                for (int i = 0; i < typeIdsSize; i++) typeDescIdx[i] = bb.getInt(typeIdsOff + i * 4);

                // field_ids -> type_idx (u2 @ +2). We only need each field's type descriptor.
                // field_id_item = class_idx(u2), type_idx(u2), name_idx(u4) = 8 bytes.
                // Read lazily below.

                for (int c = 0; c < classDefsSize; c++) {
                    int base = classDefsOff + c * 32;
                    int classIdx      = bb.getInt(base);
                    int classDataOff  = bb.getInt(base + 24);
                    if (classDataOff == 0) continue;

                    int[] p = {classDataOff};
                    long staticFields   = readUleb(bb, p);
                    long instanceFields = readUleb(bb, p);
                    long directMethods  = readUleb(bb, p);
                    long virtualMethods = readUleb(bb, p);

                    int byteArrayStatics = 0;
                    long fieldIdx = 0;
                    for (long f = 0; f < staticFields; f++) {
                        long diff  = readUleb(bb, p);
                        long flags = readUleb(bb, p);
                        fieldIdx += diff;
                        if ((flags & ACC_STATIC) == 0) continue;
                        if (fieldIdx >= fieldIdsSize) continue;
                        int typeIdx = bb.getShort(fieldIdsOff + (int) fieldIdx * 8 + 2) & 0xFFFF;
                        if (typeIdx >= typeIdsSize) continue;
                        String desc = readMutf8(bb, stringDataOff[typeDescIdx[typeIdx]]);
                        if ("[B".equals(desc)) byteArrayStatics++;
                    }

                    if (byteArrayStatics >= 2 && classIdx < typeIdsSize) {
                        String desc = readMutf8(bb, stringDataOff[typeDescIdx[classIdx]]);
                        String name = descriptorToName(desc);
                        if (name != null) out.add(name);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "dex scan failed (non-fatal): " + t);
            }
        }

        private static long readUleb(ByteBuffer bb, int[] p) {
            int result = 0, shift = 0, b;
            do {
                b = bb.get(p[0]++) & 0xFF;
                result |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return result & 0xFFFFFFFFL;
        }

        private static String readMutf8(ByteBuffer bb, int off) {
            int[] p = {off};
            readUleb(bb, p); // utf16 length (unused; we read to NUL)
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = bb.get(p[0]++) & 0xFF) != 0) {
                sb.append((char) b); // descriptors are ASCII
            }
            return sb.toString();
        }

        private static String descriptorToName(String desc) {
            if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L'
                    || desc.charAt(desc.length() - 1) != ';') return null;
            return desc.substring(1, desc.length() - 1).replace('/', '.');
        }
    }

    private AACertExtractor() {}
}
