package com.openzeekr.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.openzeekr.app.DepsHolder
import com.openzeekr.app.MainActivity
import com.openzeekr.app.R
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Command
import com.openzeekr.app.ui.theme.ThemeKeys
import com.openzeekr.app.util.Logx
import kotlinx.coroutines.withTimeout

/**
 * Home-screen quick-action widget (Jetpack Glance). Each button fires one catalog [Command]
 * through the SAME dispatcher the in-app UI uses ([com.openzeekr.app.remote.VehicleControl]):
 * BLE-first (confirmed over the live key session) with a cloud fallback. The widget itself holds
 * no account/key state - it reaches the already-constructed [com.openzeekr.app.Deps] via the
 * [DepsHolder] Application, so it works whenever the app is installed and signed in.
 *
 * Visual layer: a card in the look the user picked in the app (see [widgetColors]): the classic dark
 * "cockpit", or the Studio look in light, dark or following the system. A slim header (car nickname + a
 * status dot) sits over a grid of action tiles; colour carries meaning only (lock = secured green,
 * unlock/open = amber, Sentry = accent blue).
 * It is RESPONSIVE via [SizeMode.Exact] (the layout is chosen from the real cell size):
 *   - Small  (~2x1): Lock/Unlock toggle + Trunk.
 *   - Medium (~4x1): Lock/Unlock, Trunk, Charge port, Sentry.
 *   - Large  (~4x2): Lock/Unlock, Trunk, Charge port / Flash + honk, Sentry.
 * Lock/Unlock and Sentry are single state-aware toggles (optimistic, cached per widget).
 * The status line in the header shows the last action's progress / result (or "Open app to sign in"
 * when not configured). Only the look changes here; every button still routes through the same
 * [CommandActionCallback] and [Command] values as before.
 */
class QuickActionWidget : GlanceAppWidget() {

    // Per-widget state: the one-line status string shown in the header. Persisted via Glance's
    // default Preferences store so it survives recomposition / process death.
    override val stateDefinition = PreferencesGlanceStateDefinition

    // Pick the layout from the ACTUAL cell size (Exact), not from a fixed bucket set. Real launcher
    // 4x2 cells report well under 250x190dp, so the old Responsive buckets always collapsed to the
    // smallest (2-button) layout. With Exact, LocalSize.current is the true size and the thresholds
    // below choose compact / one-row / full.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetBody() }
    }

    companion object {
        /** Redraw every placed widget, e.g. after the theme setting changed. */
        suspend fun refreshAll(context: Context) {
            runCatching { QuickActionWidget().updateAll(context) }
        }

        // Layout thresholds against the ACTUAL widget size. A 4-wide cell is ~250dp (2-wide ~120dp),
        // so >=190dp counts as "wide"; a 2-row cell is ~110dp+ (1 row ~60-70dp), so >=90dp is "tall".
        // >=275dp is a ~5-wide cell: enough to lay ALL five actions in a single row (so a 5x1 never
        // wraps to two rows, even on a launcher whose tall cells would otherwise read as "tall").
        val WIDE_MIN = 190.dp
        val ROW5_MIN = 275.dp
        val TALL_MIN = 90.dp
    }
}

/** The single action key carries the [Command] name; the label is a pre-resolved display string. */
private val CmdKey = ActionParameters.Key<String>("oz_widget_cmd")
private val LabelKey = ActionParameters.Key<String>("oz_widget_label")

/** Preferences key for the status line (per widget instance). */
internal val StatusKey = stringPreferencesKey("oz_widget_status")

/** Last widget-driven lock state, so the Lock/Unlock toggle flips right after a tap (the car's own
 *  status poll lags). Set only on a successful LOCK/UNLOCK from the widget. */
internal val LockedKey = booleanPreferencesKey("oz_widget_locked")

/** Last widget-driven Sentry state, so the Sentry toggle flips right after a tap (no reliable live
 *  readback). Set only on a successful SENTINEL_ON/OFF from the widget. */
internal val SentryKey = booleanPreferencesKey("oz_widget_sentry")

/**
 * The widget's colours and corner radii. They mirror ui/theme/Theme.kt (a widget cannot read the app's Compose
 * theme), and are picked by the same setting: Classic pins the original dark card; the Studio look is light,
 * dark, or - for "auto" - follows the phone's light/dark setting through Glance's day/night colours.
 */
private class WColors(
    val card: ColorProvider,
    val tile: ColorProvider,
    /** Dimmer tile for the signed-out state. */
    val tileDim: ColorProvider,
    val text: ColorProvider,
    val label: ColorProvider,
    val muted: ColorProvider,
    val faint: ColorProvider,
    val accent: ColorProvider,
    val energy: ColorProvider,
    val charge: ColorProvider,
    val good: ColorProvider,
    val cardRadius: Int,
    val tileRadius: Int,
)

private fun widgetColors(theme: String): WColors {
    if (theme == ThemeKeys.CLASSIC) return WColors(
        card = ColorProvider(Color(0xFF14181C)), tile = ColorProvider(Color(0xFF1C2227)), tileDim = ColorProvider(Color(0xFF161B1F)),
        text = ColorProvider(Color(0xFFEAEEF1)), label = ColorProvider(Color(0xFFB9C2C8)), muted = ColorProvider(Color(0xFF8B979E)),
        faint = ColorProvider(Color(0xFF5E696F)), accent = ColorProvider(Color(0xFF5AA9FF)), energy = ColorProvider(Color(0xFFF5B740)),
        charge = ColorProvider(Color(0xFF31E08A)), good = ColorProvider(Color(0xFF46D8A0)),
        cardRadius = 20, tileRadius = 16,
    )
    // Studio: one light and one dark value per role.
    fun c(light: Long, dark: Long): ColorProvider = when (theme) {
        ThemeKeys.LIGHT -> ColorProvider(Color(light))
        ThemeKeys.DARK -> ColorProvider(Color(dark))
        else -> androidx.glance.color.ColorProvider(day = Color(light), night = Color(dark))
    }
    return WColors(
        card = c(0xFFFFFFFF, 0xFF14181C), tile = c(0xFFEEF0F2, 0xFF1C2227), tileDim = c(0xFFF5F6F7, 0xFF161B1F),
        text = c(0xFF0F1419, 0xFFEAEEF1), label = c(0xFF0F1419, 0xFFEAEEF1), muted = c(0xFF5B6672, 0xFF9AA6AD),
        faint = c(0xFF8E98A3, 0xFF5E696F), accent = c(0xFF1B5FD9, 0xFF5AA9FF), energy = c(0xFF9A5B00, 0xFFF5B740),
        charge = c(0xFF0B7A4F, 0xFF31E08A), good = c(0xFF0B7A4F, 0xFF46D8A0),
        cardRadius = 26, tileRadius = 18,
    )
}

/** One action tile: which command fires, the icon and its colour, and the label. */
private data class ActionSpec(
    val cmd: Command,
    val iconRes: Int,
    val tint: ColorProvider,
    val label: String,
)

@Composable
private fun WidgetBody() {
    val context = LocalContext.current
    val prefs = currentState<Preferences>()
    val size = LocalSize.current

    // Read the already-built app state (non-suspending) to drive the header: a real signed-out
    // state should look intentional, not like an error.
    val deps = (context.applicationContext as? DepsHolder)?.deps
    val cfg = deps?.config?.current()
    val ready = cfg != null && cfg.accessToken.isNotBlank() && cfg.vin.isNotBlank()
    val nickname = cfg?.carNickname?.ifBlank { context.getString(R.string.app_name) }
        ?: context.getString(R.string.app_name)
    val w = widgetColors(cfg?.theme ?: ThemeKeys.DEFAULT)

    // Status: last action result if present, else a sensible resting line.
    val status = prefs[StatusKey].orEmpty().ifBlank {
        if (ready) context.getString(R.string.widget_status_ready)
        else context.getString(R.string.widget_status_sign_in)
    }

    // Single Lock/Unlock TOGGLE: when the car reports locked ("1") the tile offers Unlock, otherwise
    // Lock. centralLockingStatus lives on drivingSafetyStatus (the same field the app + proximity use);
    // a null (state not polled yet) falls through to Lock, the safe default.
    val liveLocked = deps?.vehicleState?.state?.collectAsState()?.value
        ?.additionalVehicleStatus?.drivingSafetyStatus?.centralLockingStatus == "1"
    // Prefer the last widget-driven lock state so the toggle flips the instant you tap it (the car's
    // own status poll lags a lock/unlock); fall back to the car's reported state when nothing cached.
    val locked = prefs[LockedKey] ?: liveLocked
    val lockToggle = if (locked)
        ActionSpec(Command.UNLOCK, R.drawable.ic_widget_unlock, w.energy, context.getString(R.string.vehicle_unlock))
    else
        ActionSpec(Command.LOCK, R.drawable.ic_widget_lock, w.good, context.getString(R.string.vehicle_lock))

    // Command specs (resolved once; small/medium reuse a subset).
    val trunk = ActionSpec(Command.TRUNK_UNLOCK, R.drawable.ic_widget_trunk, w.accent, context.getString(R.string.widget_trunk_short))
    val charge = ActionSpec(Command.CHARGE_LID_OPEN, R.drawable.ic_widget_charge, w.charge, context.getString(R.string.widget_charge_short))
    val flash = ActionSpec(Command.FLASH_HORN, R.drawable.ic_widget_flash, w.energy, context.getString(R.string.widget_flash_short))

    // Single Sentry on/off TOGGLE (optimistic: Sentry has no reliable live readback, so the last
    // widget-driven state drives the tile; default off -> the tile offers "Sentry on").
    val sentryActive = prefs[SentryKey] ?: false
    val sentryToggle = if (sentryActive)
        ActionSpec(Command.SENTINEL_OFF, R.drawable.ic_widget_sentry_off, w.muted, context.getString(R.string.widget_sentry_off_short))
    else
        ActionSpec(Command.SENTINEL_ON, R.drawable.ic_widget_sentry, w.accent, context.getString(R.string.widget_sentry_on_short))

    val wide = size.width >= QuickActionWidget.WIDE_MIN
    val tall = size.height >= QuickActionWidget.TALL_MIN
    // Wide enough to fit all five actions in ONE row - checked before `large` so a wide widget never
    // wraps to two rows just because its cell is also tall.
    val allInOneRow = size.width >= QuickActionWidget.ROW5_MIN
    val large = wide && tall

    Column(
        GlanceModifier
            .fillMaxSize()
            .background(w.card)
            .cornerRadius(w.cardRadius.dp)
            .padding(if (large) 12.dp else 10.dp),
    ) {
        // Header. Large/Medium show the car name; Small shows just the dot + status to save width.
        Header(w, nickname = nickname, status = status, ready = ready, showName = wide)
        Spacer(GlanceModifier.height(if (large) 8.dp else 6.dp))

        // defaultWeight() must be resolved in THIS ColumnScope, so the row-weight modifier is built
        // here and passed down - it gives each tile row an equal share of the remaining height.
        when {
            // Wide enough for all five side by side: ONE row, even if the cell is also tall (5x1).
            allInOneRow -> TileRow(w, GlanceModifier.fillMaxWidth().defaultWeight(), ready, lockToggle, trunk, charge, flash, sentryToggle)
            // Not wide enough for five, but tall: stack into two rows (3 + 2).
            large -> {
                TileRow(w, GlanceModifier.fillMaxWidth().defaultWeight(), ready, lockToggle, trunk, charge)
                Spacer(GlanceModifier.height(6.dp))
                TileRow(w, GlanceModifier.fillMaxWidth().defaultWeight(), ready, flash, sentryToggle)
            }
            wide -> TileRow(w, GlanceModifier.fillMaxWidth().defaultWeight(), ready, lockToggle, trunk, charge, sentryToggle)
            else -> TileRow(w, GlanceModifier.fillMaxWidth().defaultWeight(), ready, lockToggle, trunk)
        }
    }
}

@Composable
private fun Header(w: WColors, nickname: String, status: String, ready: Boolean, showName: Boolean) {
    Row(
        GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot: green when signed in + car selected, amber when the app still needs opening.
        Box(
            GlanceModifier.size(8.dp).cornerRadius(4.dp)
                .background(if (ready) w.good else w.energy),
        ) {}
        Spacer(GlanceModifier.width(7.dp))
        if (showName) {
            Text(
                nickname,
                maxLines = 1,
                style = TextStyle(color = w.text, fontWeight = FontWeight.Bold, fontSize = 13.sp),
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(
            status,
            maxLines = 1,
            style = TextStyle(color = w.muted, fontSize = 11.sp, textAlign = TextAlign.End),
        )
    }
}

@Composable
private fun TileRow(w: WColors, modifier: GlanceModifier, ready: Boolean, vararg specs: ActionSpec) {
    Row(modifier) {
        specs.forEachIndexed { i, spec ->
            // defaultWeight() here resolves in the Row's content (RowScope) - equal tile widths.
            ActionTile(w, spec, ready, GlanceModifier.defaultWeight())
            if (i < specs.lastIndex) Spacer(GlanceModifier.width(6.dp))
        }
    }
}

@Composable
private fun ActionTile(w: WColors, spec: ActionSpec, ready: Boolean, modifier: GlanceModifier) {
    // Signed-out tiles are intentionally dimmed (faint icon + quieter ground) but still tappable -
    // the tap routes to the callback, which surfaces "Open app to sign in".
    val tileBg = if (ready) w.tile else w.tileDim
    val iconTint = if (ready) spec.tint else w.faint
    val labelColor = if (ready) w.label else w.faint

    Column(
        // Width comes from the defaultWeight() passed in by the row (equal tile widths); only fill
        // HEIGHT here. Using fillMaxSize()/fillMaxWidth() would force MATCH_PARENT width and cancel the
        // weight, so every row collapsed to just its first tile.
        modifier
            .fillMaxHeight()
            .cornerRadius(w.tileRadius.dp)
            .background(tileBg)
            .clickable(
                actionRunCallback<CommandActionCallback>(
                    actionParametersOf(CmdKey to spec.cmd.name, LabelKey to spec.label),
                ),
            )
            .padding(horizontal = 3.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No badge circle: the tinted icon directly. The badge was eating the tile height so the label
        // clipped in a short cell; icon + label fits a ~40dp row.
        Image(
            provider = ImageProvider(spec.iconRes),
            contentDescription = spec.label,
            modifier = GlanceModifier.size(22.dp),
            colorFilter = ColorFilter.tint(iconTint),
        )
        Spacer(GlanceModifier.height(2.dp))
        Text(
            spec.label,
            maxLines = 1,
            style = TextStyle(
                color = labelColor,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * Runs a single [Command] from a widget tap. [ActionCallback.onAction] is a suspend function, so we
 * call the suspending command dispatcher directly; Glance keeps the broadcast alive for us. The whole
 * thing is time-boxed so a stuck network can't hold the receiver open past its budget.
 */
class CommandActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val cmd = parameters[CmdKey]?.let { runCatching { Command.valueOf(it) }.getOrNull() } ?: return
        val label = parameters[LabelKey] ?: cmd.title

        val deps = (context.applicationContext as? DepsHolder)?.deps
        if (deps == null) {
            setStatusAndRender(context, glanceId, context.getString(R.string.widget_status_error))
            return
        }
        // Not signed in / no car selected: don't attempt (and don't crash) - tell the user to open the app.
        val cfg = deps.config.current()
        if (cfg.accessToken.isBlank() || cfg.vin.isBlank()) {
            setStatusAndRender(context, glanceId, context.getString(R.string.widget_status_sign_in))
            return
        }

        setStatusAndRender(context, glanceId, context.getString(R.string.vehicle_cmd_progress, label))
        val result = runCatching {
            withTimeout(WIDGET_CMD_TIMEOUT_MS) { deps.vehicleControl.send(cmd) }
        }.getOrElse { CallResult.Err(it.message ?: it.javaClass.simpleName) }
        Logx.d("widget", "${cmd.name} -> $result")

        val text = when (result) {
            is CallResult.Ok -> context.getString(R.string.vehicle_cmd_ok, label)
            is CallResult.Err ->
                // 088028 = car offline / cloud command queue full (CODE_QUEUE_FULL): say so plainly
                // instead of a raw code - the user just needs BLE range or to wake the car.
                if ("088028" in result.message.orEmpty()) context.getString(R.string.widget_status_offline)
                else context.getString(R.string.vehicle_cmd_fail, label, result.message)
        }
        setStatusAndRender(context, glanceId, text) { prefs ->
            // On a SUCCESSFUL toggle command, remember the new state so the tile flips right away.
            if (result is CallResult.Ok) when (cmd) {
                Command.LOCK -> prefs[LockedKey] = true
                Command.UNLOCK -> prefs[LockedKey] = false
                Command.SENTINEL_ON -> prefs[SentryKey] = true
                Command.SENTINEL_OFF -> prefs[SentryKey] = false
                else -> {}
            }
        }
    }

    private suspend fun setStatusAndRender(
        context: Context,
        glanceId: GlanceId,
        text: String,
        mutate: (MutablePreferences) -> Unit = {},
    ) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply { this[StatusKey] = text; mutate(this) }
        }
        QuickActionWidget().update(context, glanceId)
    }

    private companion object {
        // Kept under the broadcast/goAsync budget; the BLE path is ~1.5 s and a cloud command a few s.
        private const val WIDGET_CMD_TIMEOUT_MS = 9_000L
    }
}

/** Receiver that binds the Glance widget to the AppWidget framework (declared in the manifest). */
class QuickActionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionWidget()
}
