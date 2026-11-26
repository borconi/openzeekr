package com.openzeekr.app.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import com.openzeekr.app.Deps
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.CarCatalog
import com.openzeekr.app.ui.H3Layer
import com.openzeekr.app.ui.H3Pack
import com.openzeekr.app.ui.Hero3dAssets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The picture of the user's own car that the watch shows: the same rendered hero as on the phone's home
 * screen (model and paint), standing still with everything shut, cropped to the car and scaled down for a
 * watch. A second picture carries the charging outline, which the watch lays over the car and pulses while
 * the car charges.
 *
 * The phone makes it because the phone already holds the asset packs and the paint maths; the watch app stays
 * small and automatically follows any model or colour the phone knows. The watch keeps the last picture it
 * received, keyed by [WearCarImage.id], and only asks again when that id changes.
 */
object WearCarPicture {
    /** Bump when the way the picture is composed changes, so watches drop their stored copy. */
    private const val VERSION = 1

    /** Width of the picture sent to the watch, in pixels: enough for a 480 px round screen at 80 % width. */
    private const val TARGET_WIDTH = 400

    /** A Data Layer message tops out near 100 kB; stay well under it. */
    private const val MAX_BYTES = 88_000

    private var cachedId: String? = null
    private var cached: ByteArray? = null

    /** Which car the active VIN is (model and paint names as the cloud reports them), and when that was read. */
    private class Identity(val vin: String, val at: Long, val model: String?, val colorName: String?, val market: String?)
    private var identity: Identity? = null
    private const val IDENTITY_TTL_MS = 12 * 60 * 60 * 1000L

    /**
     * The reply to a watch that already holds the picture [knownId] (blank = none). Null when the car's
     * identity cannot be read right now (the watch then keeps what it has).
     */
    suspend fun reply(ctx: Context, deps: Deps, knownId: String): ByteArray? {
        // The watch asks every time its app comes to the front; a car's model and paint do not change, so the
        // cloud is asked once per car and then only again after a long while.
        val vin = deps.config.current().vin
        val now = System.currentTimeMillis()
        val known = synchronized(this) { identity?.takeIf { it.vin == vin && now - it.at < IDENTITY_TTL_MS } }
        val who = known ?: run {
            val info = when (val r = deps.control.vehicleInfo()) {
                is CallResult.Ok -> r.value
                is CallResult.Err -> null
            } ?: return null
            Identity(vin, now, info.model, info.colorName, info.market).also { synchronized(this) { identity = it } }
        }
        val info = who
        val model = CarCatalog.forModel(info.model)
        // No rendered pack for this model: say so (an empty id), so a watch that still shows another car's
        // picture - the user switched cars - drops it.
        if (!Hero3dAssets.available(ctx, model.key)) return WearCarImage("", null, null).encode()
        val paint = CarCatalog.colorFor(model, info.colorName, info.market)
        val id = "${Hero3dAssets.packKey(model.key)}|${Integer.toHexString(paint.color.toArgb())}${paint.roof?.let { "/" + Integer.toHexString(it.toArgb()) }.orEmpty()}|v$VERSION"
        if (id == knownId) return WearCarImage(id, null, null).encode()
        synchronized(this) { if (cachedId == id) cached?.let { return it } }
        val pack = Hero3dAssets.load(ctx, model.key, paint.color, paint.roof) ?: return null
        val bytes = withContext(Dispatchers.Default) { runCatching { compose(pack, id) }.getOrNull() } ?: return null
        synchronized(this) { cachedId = id; cached = bytes }
        return bytes
    }

    private fun compose(p: H3Pack, id: String): ByteArray? {
        // Crop: the car with a little air around it, and room below for its shadow.
        val carW = p.carRight - p.carLeft
        val carH = p.carBottom - p.carTop
        val left = max(0f, p.carLeft - 0.04f * carW).toInt()
        val right = min(p.w.toFloat(), p.carRight + 0.04f * carW).toInt()
        val top = max(0f, p.carTop - 0.10f * carH).toInt()
        val bottom = min(p.h.toFloat(), p.carBottom + 0.22f * carH).toInt()
        val cropW = right - left
        val cropH = bottom - top
        if (cropW <= 0 || cropH <= 0) return null
        val scale = min(1f, TARGET_WIDTH.toFloat() / cropW)
        val outW = (cropW * scale).roundToInt().coerceAtLeast(1)
        val outH = (cropH * scale).roundToInt().coerceAtLeast(1)

        fun picture(draw: (put: (H3Layer?) -> Unit) -> Unit): Bitmap {
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.scale(scale, scale)
            canvas.translate(-left.toFloat(), -top.toFloat())
            draw { layer -> if (layer != null) canvas.drawBitmap(layer.bmp.asAndroidBitmap(), layer.x, layer.y, paint) }
            return bmp
        }

        // The same stack, in the same order, as the phone's hero draws for a parked car with everything shut.
        val car = picture { put ->
            put(p.shadow)
            put(p.body)
            p.wheelFrames.forEach { frames -> put(frames.firstOrNull()) }
            for (name in p.partOrder) {
                val frames = p.parts[name] ?: continue
                // A shut far-side door is already part of the body layer.
                if (name.startsWith("door_far")) continue
                put(frames.firstOrNull())
                put(p.glass[name]?.firstOrNull())
            }
            put(p.glass["all"]?.firstOrNull())
        }
        // The charging outline: glow under the lines, as on the phone. White with alpha; the watch colours it.
        val neon = if (p.neonLines == null && p.neonGlow == null) null else picture { put ->
            put(p.neonGlow)
            put(p.neonLines)
        }

        var quality = 82
        while (true) {
            val carBytes = webp(car, quality)
            val neonBytes = neon?.let { webp(it, min(quality, 70)) }
            val out = WearCarImage(id, carBytes, neonBytes).encode()
            if (out.size <= MAX_BYTES || quality <= 40) {
                car.recycle(); neon?.recycle()
                return out.takeIf { it.size <= MAX_BYTES }
            }
            quality -= 14
        }
    }

    @Suppress("DEPRECATION")
    private fun webp(b: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().also { os ->
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        b.compress(format, quality, os)
    }.toByteArray()
}
