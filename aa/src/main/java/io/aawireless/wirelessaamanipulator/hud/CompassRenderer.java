package io.aawireless.wirelessaamanipulator.hud;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Renders a simple "half-compass in front of me" HUD: the car sits at the bottom
 * centre pointing up (dead ahead), and each nearby police report is a dot placed by
 * its bearing relative to the car's heading and its distance.
 *
 * Output is a bitmap sized to the car's instrument-cluster ImageOptions; the wire
 * encoding (PNG vs raw framebuffer) is selectable because different head units want
 * different things - confirm against your car's logs.
 */
public final class CompassRenderer {

    /** Alerts farther than this are pinned to the rim. */
    private static final double DISPLAY_RANGE_M = 5_000.0;
    /** Drop police reports older than this (stale). 0 = keep all. */
    private static final long MAX_AGE_MS = 6L * 60 * 60 * 1000;

    public enum WireFormat { PNG, RAW_RGB565, RAW_ARGB8888 }

    private static final int COL_POLICE = Color.argb(255, 60, 120, 230);   // blue badge
    private static final int COL_CAMERA = Color.argb(255, 240, 190, 40);   // amber camera
    private static final int COL_WHITE  = Color.WHITE;
    private static final int COL_DARK   = Color.argb(255, 25, 30, 40);

    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint carPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint status = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CompassRenderer() {
        arc.setStyle(Paint.Style.STROKE);
        arc.setColor(Color.argb(200, 120, 200, 255));
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.argb(90, 120, 200, 255));
        carPaint.setStyle(Paint.Style.FILL);
        carPaint.setColor(Color.argb(255, 90, 220, 120));
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        label.setColor(COL_WHITE);
        label.setTextAlign(Paint.Align.CENTER);
        labelBg.setStyle(Paint.Style.FILL);
        labelBg.setColor(Color.argb(150, 0, 0, 0));
        badge.setStyle(Paint.Style.FILL);
        badge.setColor(Color.argb(255, 235, 45, 45));
        badgeText.setColor(COL_WHITE);
        badgeText.setTextAlign(Paint.Align.CENTER);
        title.setColor(Color.argb(210, 150, 190, 225));
        title.setTextAlign(Paint.Align.CENTER);
        status.setTextAlign(Paint.Align.LEFT);
    }

    private static final int COL_OK   = Color.argb(255, 76, 215, 135);   // green
    private static final int COL_WARN = Color.argb(255, 255, 179, 0);    // amber
    private static final int COL_WAIT = Color.argb(200, 159, 180, 200);  // grey

    /**
     * Render the compass. Returns null if width/height are unusable.
     *
     * @param headingDeg car heading (deg clockwise from north); NaN = treat as up.
     */
    public Bitmap render(List<HudMarker> markers, double carLat, double carLng,
                         double headingDeg, int width, int height,
                         int insetL, int insetT, int insetR, int insetB,
                         long wazeLastOkMs, boolean wazeFailing) {
        if (width <= 0 || height <= 0) return null;

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.TRANSPARENT);

        // Draw only inside the cluster's safe area (the car reserves the rest - margins
        // + content insets). Everything outside this is clipped or hidden by car chrome.
        float sx0 = insetL, sy0 = insetT, sx1 = width - insetR, sy1 = height - insetB;
        if (sx1 - sx0 < 60 || sy1 - sy0 < 60) { sx0 = 0; sy0 = 0; sx1 = width; sy1 = height; }
        float sw = sx1 - sx0, sh = sy1 - sy0;

        // Forward semicircle (flat side down), with a caption strip reserved below it.
        float pad = Math.max(2f, Math.min(sw, sh) * 0.03f);
        float captionH = Math.min(Math.max(16f, sh * 0.14f), 46f);
        float radius = Math.min(sw / 2f - pad, sh - captionH - pad);
        if (radius <= 0) return bmp;
        float cx = (sx0 + sx1) / 2f;
        float cy = sy0 + pad + radius;                    // flat (diameter) side toward the bottom

        float strokeW = Math.max(1f, radius * 0.04f);
        arc.setStrokeWidth(strokeW);
        ring.setStrokeWidth(Math.max(1f, strokeW * 0.6f));

        // Range rings (half circles) at 1/3, 2/3, full.
        for (float f : new float[]{0.34f, 0.67f, 1.0f}) {
            float r = radius * f;
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 180f, 180f, false, f == 1.0f ? arc : ring);
        }

        // Vehicle marker: small upward triangle at the origin.
        float t = Math.max(3f, radius * 0.10f);
        Path car = new Path();
        car.moveTo(cx, cy - t);
        car.lineTo(cx - t * 0.7f, cy + t * 0.5f);
        car.lineTo(cx + t * 0.7f, cy + t * 0.5f);
        car.close();
        canvas.drawPath(car, carPaint);

        // Caption in the reserved strip below the arrow (kept inside the safe area).
        title.setTextSize(Math.min(captionH * 0.7f, radius * 0.11f));
        float capY = Math.min(cy + t + title.getTextSize(), sy1 - title.getTextSize() * 1.3f);
        canvas.drawText("traffic / incident monitor", cx, capY, title);

        // Waze poll status in the bottom-left of the safe area.
        status.setTextSize(Math.max(10f, radius * 0.07f));
        String statusText;
        if (wazeLastOkMs < 0) {
            if (CameraStore.isLoaded()) {
                status.setColor(COL_OK);
                statusText = "Cameras: " + CameraStore.size();
            } else {
                status.setColor(COL_WAIT);
                statusText = "Cameras: loading…";
            }
        } else if (wazeFailing) {
            status.setColor(COL_WARN);
            statusText = wazeLastOkMs > 0 ? "Waze: no data (last " + hhmm(wazeLastOkMs) + ")" : "Waze: no data";
        } else if (wazeLastOkMs > 0) {
            status.setColor(COL_OK);
            statusText = "Waze " + hhmm(wazeLastOkMs);
        } else {
            status.setColor(COL_WAIT);
            statusText = "Waze: waiting…";
        }
        canvas.drawText(statusText, sx0 + pad, sy1 - pad * 0.5f, status);

        if (markers == null) return bmp;
        long now = System.currentTimeMillis();
        float iconR = (float) Math.max(9.0, radius * 0.075);
        label.setTextSize(Math.max(11f, iconR * 0.95f));
        badgeText.setTextSize(Math.max(9f, iconR * 0.8f));

        for (HudMarker m : markers) {
            if (MAX_AGE_MS > 0 && m.publishMillis > 0 && (now - m.publishMillis) > MAX_AGE_MS) continue;

            double dist = Geo.distanceMeters(carLat, carLng, m.lat, m.lon);
            double toAlert = Geo.bearingDegrees(carLat, carLng, m.lat, m.lon);
            double heading = Double.isNaN(headingDeg) ? 0.0 : headingDeg;
            double rel = Geo.relativeBearing(heading, toAlert);   // -180..180

            if (rel < -90 || rel > 90) continue;                  // behind us -> skip

            double dNorm = Math.min(1.0, dist / DISPLAY_RANGE_M);
            double th = Math.toRadians(rel);
            float px = (float) (cx + radius * dNorm * Math.sin(th));
            float py = (float) (cy - radius * dNorm * Math.cos(th));

            if (m.cameraLike()) {
                drawCameraIcon(canvas, px, py, iconR);
            } else {
                drawPoliceIcon(canvas, px, py, iconR);
            }
            drawDistanceLabel(canvas, px, py + iconR, dist);
            if (m.reports > 0) drawReportBadge(canvas, px + iconR, py - iconR, m.reports, iconR);
        }
        return bmp;
    }

    /** Blue rounded shield with a white star = police. */
    private void drawPoliceIcon(Canvas c, float x, float y, float r) {
        Path shield = new Path();
        shield.moveTo(x, y - r);
        shield.lineTo(x + r, y - r * 0.5f);
        shield.lineTo(x + r, y + r * 0.2f);
        shield.quadTo(x + r, y + r * 0.85f, x, y + r);
        shield.quadTo(x - r, y + r * 0.85f, x - r, y + r * 0.2f);
        shield.lineTo(x - r, y - r * 0.5f);
        shield.close();
        fill.setColor(COL_POLICE);
        c.drawPath(shield, fill);
        stroke.setStrokeWidth(Math.max(1f, r * 0.12f));
        stroke.setColor(COL_WHITE);
        c.drawPath(shield, stroke);
        drawStar(c, x, y, r * 0.5f, COL_WHITE);
    }

    /** Amber square with a dark camera body + lens = mobile speed trap. */
    private void drawCameraIcon(Canvas c, float x, float y, float r) {
        fill.setColor(COL_CAMERA);
        c.drawRoundRect(x - r, y - r, x + r, y + r, r * 0.3f, r * 0.3f, fill);
        fill.setColor(COL_DARK);
        c.drawRoundRect(x - r * 0.65f, y - r * 0.3f, x + r * 0.65f, y + r * 0.55f, r * 0.2f, r * 0.2f, fill);
        fill.setColor(COL_CAMERA);
        c.drawCircle(x, y + r * 0.12f, r * 0.28f, fill);
        fill.setColor(COL_DARK);
        c.drawRect(x + r * 0.15f, y - r * 0.55f, x + r * 0.55f, y - r * 0.3f, fill); // viewfinder bump
    }

    private void drawStar(Canvas c, float cx, float cy, float r, int color) {
        Path star = new Path();
        for (int i = 0; i < 10; i++) {
            double ang = Math.PI / 2 + i * Math.PI / 5;
            float rr = (i % 2 == 0) ? r : r * 0.45f;
            float sx = (float) (cx + rr * Math.cos(ang));
            float sy = (float) (cy - rr * Math.sin(ang));
            if (i == 0) star.moveTo(sx, sy); else star.lineTo(sx, sy);
        }
        star.close();
        fill.setColor(color);
        c.drawPath(star, fill);
    }

    private void drawDistanceLabel(Canvas c, float x, float top, double meters) {
        String txt = formatDistance(meters);
        float ts = label.getTextSize();
        float w = label.measureText(txt);
        float pad = ts * 0.3f;
        float y = top + ts * 1.1f;
        c.drawRoundRect(x - w / 2 - pad, y - ts, x + w / 2 + pad, y + pad * 0.6f, pad, pad, labelBg);
        c.drawText(txt, x, y - ts * 0.15f, label);
    }

    private void drawReportBadge(Canvas c, float x, float y, int count, float r) {
        float br = r * 0.55f;
        c.drawCircle(x, y, br, badge);
        String t = count > 99 ? "99+" : String.valueOf(count);
        c.drawText(t, x, y + badgeText.getTextSize() * 0.35f, badgeText);
    }

    private static String hhmm(long ms) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    private static String formatDistance(double meters) {
        if (meters < 1000) return Math.round(meters / 10.0) * 10 + " m";
        return String.format(java.util.Locale.US, "%.1f km", meters / 1000.0);
    }

    /** Encode a rendered bitmap to the bytes the cluster expects. */
    public static byte[] encode(Bitmap bmp, WireFormat format) {
        switch (format) {
            case RAW_RGB565:    return rawRgb565(bmp);
            case RAW_ARGB8888:  return rawArgb8888(bmp);
            case PNG:
            default:
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                return bos.toByteArray();
        }
    }

    private static byte[] rawRgb565(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        ByteBuffer buf = ByteBuffer.allocate(w * h * 2).order(ByteOrder.BIG_ENDIAN);
        for (int p : px) {
            int r = (p >> 19) & 0x1F;
            int g = (p >> 10) & 0x3F;
            int b = (p >> 3) & 0x1F;
            buf.putShort((short) ((r << 11) | (g << 5) | b));
        }
        return buf.array();
    }

    private static byte[] rawArgb8888(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        ByteBuffer buf = ByteBuffer.allocate(w * h * 4).order(ByteOrder.BIG_ENDIAN);
        for (int p : px) buf.putInt(p);
        return buf.array();
    }
}
