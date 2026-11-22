package io.aawireless.wirelessaamanipulator.status;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tiny process-wide status bus so the UI can show what the background service is
 * doing without a bound-service dance. Everything is volatile + observers are
 * notified on the main thread.
 *
 * Two things matter most for debugging the Zeekr cert rejection:
 *   - {@link #certSource}: are we presenting the REAL extracted AA cert or did
 *     extraction fail? (There is deliberately no static-keystore fallback any
 *     more - a no-name unit accepts the bundled cert but a Zeekr rejects it, so
 *     silently falling back just hid the real failure.)
 *   - {@link #connectionStage}: how far the handshake/projection got.
 */
public final class AppStatus {

    public enum CertSource {
        UNKNOWN("Cert: not yet checked"),
        CHECKING("Cert: checking…"),
        REAL_EXTRACTED("Cert: real AA cert OK"),
        EXTRACTION_FAILED("Cert: extraction FAILED");

        public final String label;
        CertSource(String l) { this.label = l; }
    }

    public interface Listener {
        void onStatusChanged();
    }

    private static volatile CertSource certSource = CertSource.UNKNOWN;
    private static volatile String certDetail = "";
    private static volatile String connectionStage = "Idle";
    /** Actionable warning for the user (e.g. native Wireless AA is stealing the car). */
    private static volatile String advice = "";

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppStatus() {}

    public static CertSource getCertSource() { return certSource; }
    public static String getCertDetail() { return certDetail; }
    public static String getConnectionStage() { return connectionStage; }
    public static String getAdvice() { return advice; }

    /** Raise an actionable warning. No-op if the same advice is already showing. */
    public static void setAdvice(String text) {
        String t = text != null ? text : "";
        if (t.equals(advice)) return;
        advice = t;
        notifyChanged();
    }

    public static void clearAdvice() { setAdvice(""); }

    public static void setCert(CertSource source, String detail) {
        certSource = source != null ? source : CertSource.UNKNOWN;
        certDetail = detail != null ? detail : "";
        notifyChanged();
    }

    public static void setConnectionStage(String stage) {
        connectionStage = stage != null ? stage : "";
        notifyChanged();
    }

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyChanged() {
        for (Listener l : listeners) {
            MAIN.post(l::onStatusChanged);
        }
    }
}
