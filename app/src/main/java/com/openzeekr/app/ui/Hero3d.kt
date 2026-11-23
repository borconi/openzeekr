package com.openzeekr.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openzeekr.app.remote.Command
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * An animated hero car built from layers rendered offline from a 3D model, all on one fixed camera angle
 * (see WIP_DEV_HERO.md in the repo root).
 * Shown instead of the static [Hero] card whenever an asset pack for the car exists under
 * assets/cars/hero3d/<key>/ (packs so far: 7gt, 7x). That folder is git-ignored, like the other car images,
 * so a build without the pack, or a model without one, silently keeps the old hero.
 *
 * Colour: every paint-affected layer ships twice, rendered with near-white paint (W) and black paint (K).
 * Any paint colour is then K + tint * (W - K) in linear light, computed once per colour when the pack loads.
 * So one pack serves every colour and a new colour needs no new images.
 *
 * Missing frames are intentional, not an export gap: a null frame in the manifest means nothing of that part
 * shows from the camera in that pose. The camera looks at the car's right-hand side, so the far-side rear door
 * is behind the car even when fully open (opening it changes about 0.03% of the picture); such a part is left
 * out of "parts"/"part_order" and listed under "hidden_parts". A far-side door's frame 0 is always null because
 * the shut door is drawn as part of the body layer.
 */

private const val HERO3D_ROOT = "cars/hero3d"

/** Countries that drive on the left (right-hand-drive cars); used only when the cloud config does not say. */
internal val RHD_COUNTRIES = setOf(
    "AU", "NZ", "GB", "UK", "IE", "MT", "CY", "HK", "MO", "SG", "MY", "TH", "ID", "BN", "JP", "IN", "PK", "BD", "LK", "NP",
    "ZA", "KE", "TZ", "UG", "ZM", "ZW", "BW", "NA", "MZ", "MU", "JM", "TT", "BB", "BS", "FJ", "PG",
)

/** What the car is doing right now, as far as the hero needs to know. "Near" is the side facing the camera
 *  (the car's right-hand side), "far" is the other one. */
data class Hero3dStatus(
    val doorNearFront: Boolean = false,
    val doorNearRear: Boolean = false,
    val doorFarFront: Boolean = false,
    val doorFarRear: Boolean = false,
    val hood: Boolean = false,
    val tailgate: Boolean = false,
    /** 0 = side windows closed, 1 = fully down. */
    val window: Float = 0f,
    val speedKmh: Float = 0f,
    val charging: Boolean = false,
    /** State of charge 0..1; the charging glow covers this share of the car, starting at the rear. */
    val soc: Float = 0f,
)

/** One-shot effects and short-lived "the user just asked for this" overrides, so an animation starts the
 *  moment a command is sent instead of waiting for the car's next status report. */
@Stable
class Hero3dFx {
    var flashTick by mutableIntStateOf(0)
        private set
    private val overrides = mutableStateMapOf<String, Float>()
    private val tokens = HashMap<String, Int>()

    fun flash() { flashTick++ }

    fun expect(scope: CoroutineScope, part: String, value: Float, holdMs: Long = 15_000L) {
        val token = (tokens[part] ?: 0) + 1
        tokens[part] = token
        overrides[part] = value
        scope.launch {
            delay(holdMs)
            if (tokens[part] == token) overrides.remove(part)   // after that, the car's own report wins again
        }
    }

    fun resolve(part: String, actual: Float): Float = overrides[part] ?: actual

    fun onCommand(scope: CoroutineScope, c: Command) {
        when (c) {
            Command.FLASH, Command.FLASH_HORN -> flash()
            Command.FRONT_TRUNK -> expect(scope, "hood", 1f)
            Command.TRUNK_OPEN, Command.TRUNK_UNLOCK -> expect(scope, "tailgate", 1f)
            Command.TRUNK_LOCK -> expect(scope, "tailgate", 0f)
            Command.WINDOW_OPEN -> expect(scope, "window", 1f)
            Command.WINDOW_VENT -> expect(scope, "window", 0.25f)
            Command.WINDOW_CLOSE -> expect(scope, "window", 0f)
            else -> {}
        }
    }
}

internal class H3Layer(val bmp: ImageBitmap, val x: Float, val y: Float)

internal class H3Pack(
    val w: Int, val h: Int, val steps: Int,
    /** Rolling radius in metres, so the wheels turn at the right rate for the road speed. */
    val wheelRadius: Float,
    val carLeft: Float, val carRight: Float,
    val shadow: H3Layer?, val body: H3Layer,
    val partOrder: List<String>,
    val parts: Map<String, List<H3Layer?>>,
    val glass: Map<String, List<H3Layer?>>,
    val wheelFrames: List<List<H3Layer>>, val wheelSmear: List<H3Layer>,
    val lights: H3Layer?, val neonLines: H3Layer?, val neonGlow: H3Layer?,
)

object Hero3dAssets {
    private var cacheKey: String? = null
    private var cached: H3Pack? = null

    /** Asset-pack folder name for a catalogue model key, e.g. "7GT" -> "7gt". */
    fun packKey(modelKey: String): String = modelKey.lowercase()

    fun available(ctx: Context, modelKey: String): Boolean = runCatching {
        ctx.assets.open("$HERO3D_ROOT/${packKey(modelKey)}/manifest.json").close(); true
    }.getOrDefault(false)

    private val toLin = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    private val toSrgb = IntArray(4096) { i ->
        val l = i / 4095f
        val s = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
        (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun decode(ctx: Context, dir: String, file: String): Bitmap? = runCatching {
        ctx.assets.open("$dir/$file").use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun plain(ctx: Context, dir: String, o: JSONObject?): H3Layer? {
        o ?: return null
        val b = decode(ctx, dir, o.getString("f")) ?: return null
        return H3Layer(b.asImageBitmap(), o.getInt("x").toFloat(), o.getInt("y").toFloat())
    }

    /** K + tint * (W - K) per channel in linear light; [tint] is already divided by the white render's albedo. */
    private fun painted(ctx: Context, dir: String, o: JSONObject?, tint: FloatArray, curve: IntArray): H3Layer? {
        o ?: return null
        val wb = decode(ctx, dir, o.getString("w")) ?: return null
        val kb = decode(ctx, dir, o.getString("k")) ?: return null
        val w = min(wb.width, kb.width)
        val h = min(wb.height, kb.height)
        val pw = IntArray(w * h)
        val pk = IntArray(w * h)
        wb.getPixels(pw, 0, w, 0, 0, w, h)
        kb.getPixels(pk, 0, w, 0, 0, w, h)
        wb.recycle(); kb.recycle()
        for (i in pw.indices) {
            val cw = pw[i]
            val ck = pk[i]
            val a = max(cw ushr 24, ck ushr 24)
            if (a == 0) { pw[i] = 0; continue }
            var out = a shl 24
            var shift = 16
            for (ch in 0..2) {
                val lw = toLin[(cw shr shift) and 0xFF]
                val lk = toLin[(ck shr shift) and 0xFF]
                val l = (lk + tint[ch] * (lw - lk)).coerceIn(0f, 1f)
                out = out or (curve[toSrgb[(l * 4095f).toInt()]] shl shift)
                shift -= 8
            }
            pw[i] = out
        }
        val bmp = Bitmap.createBitmap(pw, w, h, Bitmap.Config.ARGB_8888)
        return H3Layer(bmp.asImageBitmap(), o.getInt("x").toFloat(), o.getInt("y").toFloat())
    }

    internal suspend fun load(ctx: Context, modelKey: String, paint: Color): H3Pack? = withContext(Dispatchers.Default) {
        val key = packKey(modelKey)
        val id = "$key|${paint.value}"
        synchronized(this@Hero3dAssets) { if (cacheKey == id) return@withContext cached }
        val dir = "$HERO3D_ROOT/$key"
        val pack = runCatching {
            val m = JSONObject(ctx.assets.open("$dir/manifest.json").bufferedReader().use { it.readText() })
            val albedo = m.optDouble("w_albedo", 0.8).toFloat()
            fun lin(c: Float) = toLin[(c * 255f + 0.5f).toInt().coerceIn(0, 255)]
            val tint = floatArrayOf(lin(paint.red) / albedo, lin(paint.green) / albedo, lin(paint.blue) / albedo)
            // The renders are deliberately flat (plain sRGB, so the colour maths above is exact); this curve
            // puts the contrast back. Layers without paint had it applied when the pack was built.
            val cj = m.optJSONObject("curve")
            val black = cj?.optDouble("black", 0.0)?.toFloat() ?: 0f
            val gamma = cj?.optDouble("gamma", 1.0)?.toFloat() ?: 1f
            val curve = IntArray(256) { i -> (((i / 255f - black) / (1f - black)).coerceIn(0f, 1f).pow(gamma) * 255f + 0.5f).toInt() }
            val canvas = m.getJSONArray("canvas")
            val steps = m.getInt("steps")
            val carX = m.getJSONArray("car_x")
            val order = m.getJSONArray("part_order").let { a -> List(a.length()) { a.getString(it) } }
            val partsJ = m.getJSONObject("parts")
            val parts = order.associateWith { name ->
                val arr = partsJ.getJSONArray(name)
                List(arr.length()) { i -> painted(ctx, dir, arr.optJSONObject(i), tint, curve) }
            }
            val glassJ = m.optJSONObject("glass") ?: JSONObject()
            val glass = glassJ.keys().asSequence().associateWith { name ->
                val arr = glassJ.getJSONArray(name)
                List(arr.length()) { i -> plain(ctx, dir, arr.optJSONObject(i)) }
            }
            val wheelsJ = m.getJSONArray("wheels")
            val wheelFrames = List(wheelsJ.length()) { wi ->
                val fr = wheelsJ.getJSONObject(wi).getJSONArray("frames")
                List(fr.length()) { i -> plain(ctx, dir, fr.getJSONObject(i)) }.filterNotNull()
            }
            val wheelSmear = List(wheelsJ.length()) { wi -> plain(ctx, dir, wheelsJ.getJSONObject(wi).getJSONObject("smear")) }.filterNotNull()
            H3Pack(
                w = canvas.getInt(0), h = canvas.getInt(1), steps = steps,
                wheelRadius = m.optDouble("wheel_radius", 0.36).toFloat(),
                carLeft = carX.getInt(0).toFloat(), carRight = carX.getInt(1).toFloat(),
                shadow = plain(ctx, dir, m.optJSONObject("shadow")),
                body = painted(ctx, dir, m.getJSONObject("body"), tint, curve) ?: error("body layer missing"),
                partOrder = order, parts = parts, glass = glass,
                wheelFrames = wheelFrames, wheelSmear = wheelSmear,
                lights = plain(ctx, dir, m.optJSONObject("lights")),
                neonLines = plain(ctx, dir, m.optJSONObject("neon_lines")),
                neonGlow = plain(ctx, dir, m.optJSONObject("neon_glow")),
            )
        }.getOrNull()
        synchronized(this@Hero3dAssets) { cacheKey = id; cached = pack }
        pack
    }
}

/** The animated hero: the car itself, no card behind it. Falls back to nothing but a spinner while loading;
 *  the caller decides (via [Hero3dAssets.available]) whether to use this or the classic hero at all. */
@Composable
fun Hero3d(modelKey: String, paint: Color, status: Hero3dStatus, fx: Hero3dFx, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current.applicationContext
    var pack by remember(modelKey, paint) { mutableStateOf<H3Pack?>(null) }
    LaunchedEffect(modelKey, paint) { pack = Hero3dAssets.load(ctx, modelKey, paint) }
    Box(modifier.fillMaxWidth().padding(horizontal = 6.dp).aspectRatio(2f), contentAlignment = Alignment.Center) {
        val p = pack
        if (p == null) CircularProgressIndicator(Modifier.size(30.dp), color = Brand.muted, strokeWidth = 3.dp)
        else Hero3dCanvas(p, status, fx)
    }
}

@Composable
private fun Hero3dCanvas(p: H3Pack, status: Hero3dStatus, fx: Hero3dFx) {
    @Composable
    fun part(name: String, open: Boolean, ms: Int = 900): Float {
        val v by animateFloatAsState(fx.resolve(name, if (open) 1f else 0f), tween(ms, easing = LinearEasing), label = name)
        return v
    }
    val amounts = mapOf(
        "door_near_front" to part("door_near_front", status.doorNearFront),
        "door_near_rear" to part("door_near_rear", status.doorNearRear),
        "door_far_front" to part("door_far_front", status.doorFarFront),
        "door_far_rear" to part("door_far_rear", status.doorFarRear),
        "hood" to part("hood", status.hood, 1100),
        "tailgate" to part("tailgate", status.tailgate, 1300),
    )
    val window by animateFloatAsState(fx.resolve("window", status.window), tween(1400, easing = LinearEasing), label = "window")

    // Wheels and passing ground only tick while the car is actually moving.
    val speed by rememberUpdatedState(status.speedKmh)
    var wheelDeg by remember { mutableFloatStateOf(0f) }
    var roadPx by remember { mutableFloatStateOf(0f) }
    val moving = status.speedKmh > 0.5f
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) {
                val dt = (now - last) / 1e9f
                val mps = speed / 3.6f
                // Above walking pace a true-speed wheel would strobe on a 60 Hz screen, so cap what is drawn.
                val degPerS = min(Math.toDegrees((mps / p.wheelRadius).toDouble()).toFloat(), 420f)
                wheelDeg = (wheelDeg + degPerS * dt) % 72f
                roadPx = (roadPx + min(mps, 30f) * 60f * dt) % 100_000f
            }
            last = now
        }
    }
    val smear by animateFloatAsState(((status.speedKmh - 12f) / 28f).coerceIn(0f, 1f), tween(500), label = "smear")

    // Headlight flash: three quick pulses each time a flash command goes out.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(fx.flashTick) {
        if (fx.flashTick == 0) return@LaunchedEffect
        repeat(3) {
            flash.animateTo(1f, tween(110))
            flash.animateTo(0f, tween(190))
        }
    }

    // Charging: a neon outline that fills from the rear up to the state of charge, with a band sweeping through it.
    val charge by animateFloatAsState(if (status.charging) status.soc.coerceIn(0f, 1f) else 0f, tween(1200), label = "charge")
    val loop = rememberInfiniteTransition(label = "charge-loop")
    val sweep by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    val pulse by loop.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse")
    val streak = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)

    Canvas(Modifier.fillMaxSize()) {
        val sc = size.width / p.w
        scale(sc, sc, pivot = Offset.Zero) {
            fun layer(l: H3Layer?, alpha: Float = 1f, tint: Color? = null, blend: BlendMode = BlendMode.SrcOver) {
                if (l == null || alpha <= 0f) return
                drawImage(l.bmp, topLeft = Offset(l.x, l.y), alpha = alpha,
                    colorFilter = tint?.let { ColorFilter.tint(it) }, blendMode = blend)
            }
            if (moving) streaks(p, roadPx, streak)
            layer(p.shadow)
            layer(p.body)

            p.wheelFrames.forEachIndexed { i, frames ->
                if (frames.isEmpty()) return@forEachIndexed
                val idx = ((wheelDeg / 72f) * frames.size).toInt().coerceIn(0, frames.size - 1)
                layer(frames[idx], alpha = 1f)
                layer(p.wheelSmear.getOrNull(i), alpha = smear)
            }

            var doorsShut = true
            for (name in p.partOrder) {
                val frames = p.parts[name] ?: continue
                val idx = ((amounts[name] ?: 0f) * p.steps).roundToInt().coerceIn(0, frames.size - 1)
                // A closed far-side door is already part of the body layer; only its open frames are separate.
                if (idx == 0 && name.startsWith("door_far")) continue
                if (idx > 0 && name.startsWith("door")) doorsShut = false
                layer(frames[idx])
                // The glass of a shut near door is its own layer so that the windows can slide down.
                if (idx == 0) p.glass[name]?.let { g -> layer(g[(window * p.steps).roundToInt().coerceIn(0, g.size - 1)]) }
            }
            if (doorsShut) p.glass["all"]?.let { g -> layer(g[(window * p.steps).roundToInt().coerceIn(0, g.size - 1)]) }

            layer(p.lights, alpha = flash.value, blend = BlendMode.Plus)

            if (charge > 0.005f) {
                // Drawn into its own additive layer and then faded at the edges, so the lit part ends softly
                // instead of at a hard vertical cut.
                fun neon(left: Float, right: Float, fadeL: Float, fadeR: Float, glow: Float, lines: Float, lineTint: Color) {
                    val w = right - left
                    if (w <= 1f) return
                    val cv = drawContext.canvas
                    cv.saveLayer(Rect(left, 0f, right, p.h.toFloat()), Paint().apply { blendMode = BlendMode.Plus })
                    layer(p.neonGlow, alpha = glow, tint = Brand.neon)
                    layer(p.neonLines, alpha = lines, tint = lineTint)
                    val fl = (fadeL / w).coerceIn(0f, 0.49f)
                    val fr = (fadeR / w).coerceIn(0f, 0.49f)
                    drawRect(
                        Brush.horizontalGradient(
                            0f to (if (fl > 0f) Color.Transparent else Color.Black), fl to Color.Black,
                            1f - fr to Color.Black, 1f to (if (fr > 0f) Color.Transparent else Color.Black),
                            startX = left, endX = right,
                        ),
                        topLeft = Offset(left, 0f), size = Size(w, p.h.toFloat()), blendMode = BlendMode.DstIn,
                    )
                    cv.restore()
                }
                val x0 = p.carLeft
                val x1 = x0 + (p.carRight - p.carLeft) * charge
                neon(x0, x1, 0f, if (charge < 0.995f) 90f else 0f, 0.5f * pulse, 0.85f, Brand.neon)
                val band = 170f
                val bx = x0 - band + (x1 - x0 + band) * sweep
                neon(max(x0, bx), min(x1, bx + band), 70f, 70f, 0.85f, 1f, Color.White)
            }
        }
    }
}

/** Faint ground streaks sliding towards the rear (the nose points right on this camera angle). */
private fun DrawScope.streaks(p: H3Pack, roadPx: Float, color: Color) {
    val rows = floatArrayOf(0.30f, 0.42f, 0.83f, 0.88f, 0.93f)
    val lens = floatArrayOf(120f, 70f, 170f, 110f, 150f)
    val span = p.w + 260f
    for (i in rows.indices) {
        val x = span - ((roadPx * (1f + (i % 3) * 0.35f) + i * 331f) % span) - 200f
        val y = p.h * rows[i]
        drawLine(color, Offset(x, y), Offset(x + lens[i], y), strokeWidth = 3.5f, cap = StrokeCap.Round)
    }
}
