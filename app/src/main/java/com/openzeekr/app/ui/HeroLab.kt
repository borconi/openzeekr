package com.openzeekr.app.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.ui.theme.Brand

/**
 * Hero Lab — a self-contained graphics sandbox (reached from Settings → App). Renders the layered
 * hero car in the three vehicle states so we can iterate on the look without touching the live
 * Vehicle screen: Still, Charging (energy breathe + SoC shimmer), Driving (moving road dashes +
 * suspension bob). Model + colour switchers on top. Uses the LOCAL two-layer assets + the same
 * recolour maths as the real hero (helpers duplicated here so the lab stays isolated).
 */
private enum class HeroMode(val label: String) { STILL("Still"), CHARGING("Charging"), DRIVING("Driving") }

@Composable
fun HeroLabScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val models = remember { CarCatalog.models.filter { it.bodyAsset != null } } // layered models (7GT, 7X)
    var modelIdx by remember { mutableStateOf(models.indexOfFirst { it.key == "7GT" }.coerceAtLeast(0)) }
    val model = models.getOrElse(modelIdx) { models.first() }
    var paint by remember(model) { mutableStateOf(model.colors.first()) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack).padding(8.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Hero Lab", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            models.forEachIndexed { i, m -> LabChip(m.displayName, selected = i == modelIdx) { modelIdx = i } }
        }
        Spacer(Modifier.height(10.dp))
        LabSwatches(model.colors, paint) { paint = it }

        HeroMode.entries.forEach { mode ->
            Text(
                mode.label, color = Brand.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 2.dp),
            )
            LabHeroCard(model, paint, mode)
        }
    }
}

@Composable
private fun LabHeroCard(model: CarModel, paint: PaintColor, mode: HeroMode) {
    val body = labAsset(model.bodyAsset)
    val details = labAsset(model.detailsAsset)
    val trans = rememberInfiniteTransition(label = mode.label)
    val breathe by trans.animateFloat(0.04f, 0.24f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe")
    val roadOffset by trans.animateFloat(0f, 180f, infiniteRepeatable(tween(300, easing = LinearEasing), RepeatMode.Restart), label = "road")
    val socPhase by trans.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "soc")
    val streak by trans.animateFloat(0f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Restart), label = "streak")

    val bg: Brush = if (model.key == "7GT") SolidColor(Color.White)
    else Brush.radialGradient(listOf(Color(0xFFCED1D6), Color(0xFFA6A9AF)))
    val filter = paint.recolor?.let { ColorFilter.colorMatrix(labScale(it)) }
        ?: ColorFilter.colorMatrix(labLum(paint.color))

    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            .height(196.dp).clip(RoundedCornerShape(22.dp)).background(bg),
    ) {
        if (mode == HeroMode.CHARGING) Box(Modifier.matchParentSize().background(Brand.good.copy(alpha = breathe)))

        if (mode == HeroMode.DRIVING) {
            // Speed streaks sweeping right→left behind the car — the bob alone read as "just
            // bouncing"; these sell forward motion. Neutral slate so they show on white and grey cards.
            Canvas(Modifier.matchParentSize()) {
                val rows = 6
                for (i in 0 until rows) {
                    val yy = size.height * (0.26f + 0.48f * (i / (rows - 1f)))
                    val len = 46f + (i % 3) * 44f
                    val p = (streak + i / rows.toFloat()) % 1f
                    val x = size.width * (1f - p) - len
                    drawLine(
                        color = Color(0xFF8A9099).copy(alpha = 0.10f + (i % 3) * 0.05f),
                        start = Offset(x, yy), end = Offset(x + len, yy),
                        strokeWidth = 3.5f, cap = StrokeCap.Round,
                    )
                }
            }
            // Road dashes streaming past at the base.
            Canvas(
                Modifier.fillMaxWidth().height(12.dp).align(Alignment.BottomCenter).padding(bottom = 22.dp),
            ) {
                val lineLength = 90f
                val gap = 90f
                val y = size.height / 2f
                for (i in -1..14) {
                    val startX = i * (lineLength + gap) - roadOffset
                    drawLine(
                        color = Color.DarkGray.copy(alpha = 0.45f),
                        start = Offset(startX, y), end = Offset(startX + lineLength, y),
                        strokeWidth = 7f, cap = StrokeCap.Round,
                    )
                }
            }
        }

        val carMod = Modifier.fillMaxWidth().align(Alignment.Center)
            .padding(horizontal = 4.dp).aspectRatio(16f / 8f)
        if (body != null && details != null) {
            Image(bitmap = body, contentDescription = model.displayName, modifier = carMod, colorFilter = filter)
            Image(bitmap = details, contentDescription = null, modifier = carMod)
        }

        Row(
            Modifier.align(Alignment.BottomStart).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(labSwatchColor(paint)))
            Text(paint.name, color = Brand.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        if (mode == HeroMode.CHARGING) {
            val shimmer = Brush.linearGradient(
                0f to Brand.good.copy(alpha = .55f), 0.5f to Color.White.copy(alpha = .85f), 1f to Brand.good.copy(alpha = .55f),
                start = Offset(-160f + socPhase * 320f, 0f), end = Offset(socPhase * 320f, 0f), tileMode = TileMode.Mirror,
            )
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .10f))) {
                Box(Modifier.fillMaxWidth(0.7f).height(4.dp).background(shimmer))
            }
        }
    }
}

// ---- lab-local helpers (kept separate so the sandbox never touches the live Vehicle screen) ----

@Composable
private fun labAsset(path: String?): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(path) {
        if (path == null) null
        else runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }.asImageBitmap() }.getOrNull()
    }
}

private fun labScale(rgb: FloatArray) = ColorMatrix(
    floatArrayOf(
        rgb.getOrElse(0) { 1f }, 0f, 0f, 0f, 0f,
        0f, rgb.getOrElse(1) { 1f }, 0f, 0f, 0f,
        0f, 0f, rgb.getOrElse(2) { 1f }, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
)

private fun labLum(c: Color) = ColorMatrix(
    floatArrayOf(
        0.2126f * c.red, 0.7152f * c.red, 0.0722f * c.red, 0f, 0f,
        0.2126f * c.green, 0.7152f * c.green, 0.0722f * c.green, 0f, 0f,
        0.2126f * c.blue, 0.7152f * c.blue, 0.0722f * c.blue, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
)

private fun labSwatchColor(p: PaintColor): Color =
    p.recolor?.let { Color(it.getOrElse(0) { 1f }, it.getOrElse(1) { 1f }, it.getOrElse(2) { 1f }, 1f) } ?: p.color

@Composable
private fun LabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(if (selected) Brand.accent.copy(alpha = .18f) else Brand.surface2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (selected) Brand.accent else Brand.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LabSwatches(colors: List<PaintColor>, selected: PaintColor, onPick: (PaintColor) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEach { pc ->
            val sel = pc.name == selected.name
            Box(
                Modifier.size(if (sel) 30.dp else 26.dp).clip(CircleShape).background(labSwatchColor(pc))
                    .border(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.onSurface else Brand.line, CircleShape)
                    .clickable { onPick(pc) },
            )
        }
    }
}
