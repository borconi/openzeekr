package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import com.openzeekr.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.ble.rpa.RpaReq
import com.openzeekr.app.ui.theme.Brand

/**
 * What the remote-parking controls can ask of the car. Built once from the controller and handed to whichever
 * look is drawn, so a button in Classic and the same button in Studio always run the same [RpaController] call.
 */
internal class RpaActions(rpa: RpaController) {
    val connect: () -> Unit = { rpa.begin() }
    val stop: () -> Unit = { rpa.stop() }
    val blindProbe: () -> Unit = { rpa.blindProbe() }
    val searchSlot: () -> Unit = { rpa.searchSlot() }
    val parkIn: () -> Unit = { rpa.startParkIn() }
    val continueParking: () -> Unit = { rpa.continueParking() }
    val pause: () -> Unit = { rpa.pause() }
    val undo: () -> Unit = { rpa.undo() }
    /** Takes one of the [RpaReq] `*_OUT` directions. */
    val parkOut: (Byte) -> Unit = { rpa.startParkOut(it) }
    /** Press: true = forward, false = backward. Must be followed by [releaseMove] when the finger lifts. */
    val holdMove: (Boolean) -> Unit = { rpa.holdMove(it) }
    val releaseMove: () -> Unit = { rpa.releaseMove() }
}

/** The link is being opened: Connect shows its busy state. */
internal val RpaController.UiState.connecting: Boolean get() = phase == RpaController.Phase.CONNECTING
/** No session is open (never started, failed or finished), so Connect is offered. */
internal val RpaController.UiState.canConnect: Boolean
    get() = phase == RpaController.Phase.IDLE || phase == RpaController.Phase.ERROR || phase == RpaController.Phase.DONE
/** A session is open or opening, so Stop is offered. */
internal val RpaController.UiState.active: Boolean
    get() = phase != RpaController.Phase.IDLE && phase != RpaController.Phase.ERROR && phase != RpaController.Phase.DONE

/** The word and the colour for a remote-parking phase. */
@Composable
internal fun rpaPhaseStyle(phase: RpaController.Phase): Pair<String, Color> = when (phase) {
    RpaController.Phase.READY -> stringResource(R.string.parking_phase_ready) to Brand.good
    RpaController.Phase.MOVING, RpaController.Phase.PARKING_IN, RpaController.Phase.PARKING_OUT -> stringResource(R.string.parking_phase_active) to Brand.accent
    RpaController.Phase.PAUSED -> stringResource(R.string.parking_phase_paused) to Brand.energy
    RpaController.Phase.DONE -> stringResource(R.string.parking_phase_done) to Brand.good
    RpaController.Phase.ERROR -> stringResource(R.string.parking_phase_error) to Brand.crit
    RpaController.Phase.CONNECTING -> stringResource(R.string.parking_phase_connecting) to Brand.accent
    else -> stringResource(R.string.parking_phase_idle) to Brand.faint
}

/** How the car leaves a space: out of a parallel space, or out of a perpendicular one nose first or tail first. */
internal enum class ParkOutKind { PARALLEL, HEAD_PERPENDICULAR, TAIL_PERPENDICULAR }

/** One park-out choice: its label, the side the car leaves to, and the direction code sent to the car. */
internal class ParkOutOption(val labelRes: Int, val kind: ParkOutKind, val toRight: Boolean, val direction: Byte)

/** Every park-out choice, in the order the Classic look lists them. */
internal val ParkOutOptions = listOf(
    ParkOutOption(R.string.parking_parallel_r, ParkOutKind.PARALLEL, true, RpaReq.PARALLEL_RIGHT_OUT),
    ParkOutOption(R.string.parking_parallel_l, ParkOutKind.PARALLEL, false, RpaReq.PARALLEL_LEFT_OUT),
    ParkOutOption(R.string.parking_head_perp_r, ParkOutKind.HEAD_PERPENDICULAR, true, RpaReq.HEAD_PERPENDICULAR_RIGHT_OUT),
    ParkOutOption(R.string.parking_head_perp_l, ParkOutKind.HEAD_PERPENDICULAR, false, RpaReq.HEAD_PERPENDICULAR_LEFT_OUT),
    ParkOutOption(R.string.parking_tail_perp_r, ParkOutKind.TAIL_PERPENDICULAR, true, RpaReq.TAIL_PERPENDICULAR_RIGHT_OUT),
    ParkOutOption(R.string.parking_tail_perp_l, ParkOutKind.TAIL_PERPENDICULAR, false, RpaReq.TAIL_PERPENDICULAR_LEFT_OUT),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParkingScreen(deps: Deps, modifier: Modifier = Modifier) {
    val state by deps.rpa.state.collectAsState()
    val cfg by deps.config.config.collectAsState()
    val rpa = remember(deps.rpa) { RpaActions(deps.rpa) }
    if (Brand.isStudio) {
        // The Studio look: the same position, the same navigation hand-off and the same remote-parking calls;
        // only the layout (ParkingStudio.kt) is different.
        val ctx = LocalContext.current
        val loc = rememberCarLocation(deps)
        val pos = loc.pos
        StudioLocationPage(
            StudioLocationState(
                lat = pos?.first, lng = pos?.second,
                // Street address from the phone's own geocoder (the owner chose to have it, 2026-11-25). This hands
                // the car position to the phone's geocoding service, once per position: results are cached for
                // the life of the process. Pass null to show coordinates only.
                address = rememberCarAddress(pos),
                updated = if (pos != null) loc.updatedAt?.takeIf { it > 0L }?.let { formatLocationTime(ctx, it) } else null,
                devMode = cfg.devMode,
                rpa = state,
            ),
            onNavigate = { if (pos != null) navigateToCar(ctx, pos.first, pos.second) },
            rpa = rpa,
            modifier = modifier,
        )
        return
    }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Where's my car — MapLibre location + navigate deeplink. Always shown.
        CarLocationSection(deps)

        // Remote Parking is an IN-DEVELOPMENT tool, gated behind developer mode (Settings -> tap the
        // version 10x). Normal users only see "where's my car" above. (Proximity calibration + car-side
        // passive-entry toggles now live in the Key tab's Passive-entry section, not here.)
        if (cfg.devMode) {
            // Remote parking — the car engages RPA over the ordinary BLE DK session (REQ_MODE ACKed
            // 0x1000, streams 0x117 SYNC, issues 0x115 challenges); NOT DK-3.0/SE tier-walled. To
            // actually maneuver, Remote Parking must be ARMED on the car's centre screen first.
            RemoteParkingControls(rpa, state)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteParkingControls(rpa: RpaActions, state: RpaController.UiState) {
    val phase = state.phase
    val connecting = state.connecting
    val canConnect = state.canConnect
    val active = state.active

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocalParking, null, tint = Brand.accent, modifier = Modifier.size(21.dp))
            }
            Text(stringResource(R.string.parking_title), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            PhasePill(phase, connecting)
        }
        state.message?.let {
            Text(it, color = if (phase == RpaController.Phase.ERROR) Brand.crit else Brand.muted, fontSize = 12.5.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (connecting) stringResource(R.string.parking_connecting) else stringResource(R.string.parking_connect), Modifier.weight(1f), enabled = canConnect) { rpa.connect() }
            GhostButton(stringResource(R.string.parking_stop), Modifier.weight(1f), enabled = active, tint = Brand.crit) { rpa.stop() }
        }

        // DEBUG: fire every maneuver opcode once (skips the REQ_MODE→SYNC gate) to learn if
        // any command downstream gets a non-0x100a response. RPA is absent from stock Android
        // (iOS/DK3.0/UWB-gated), so this is exploratory. ⚠️ Keep clear space around the car.
        GhostButton(stringResource(R.string.parking_blind_probe), Modifier.fillMaxWidth(), tint = Brand.energy) { rpa.blindProbe() }
        Text(stringResource(R.string.parking_blind_probe_hint),
            color = Brand.faint, fontSize = 11.sp)

        SectionHeader(stringResource(R.string.parking_section_park_in))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(stringResource(R.string.parking_search), Modifier.weight(1f)) { rpa.searchSlot() }
            Tile(stringResource(R.string.parking_park_in), Modifier.weight(1f)) { rpa.parkIn() }
            Tile(stringResource(R.string.parking_continue), Modifier.weight(1f)) { rpa.continueParking() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(stringResource(R.string.parking_pause), Modifier.weight(1f)) { rpa.pause() }
            Tile(stringResource(R.string.parking_undo), Modifier.weight(1f)) { rpa.undo() }
            Spacer(Modifier.weight(1f))
        }

        SectionHeader(stringResource(R.string.parking_section_park_out))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ParkOutOptions.forEach { o -> Chip(stringResource(o.labelRes)) { rpa.parkOut(o.direction) } }
        }

        SectionHeader(stringResource(R.string.parking_section_hold_to_move))
        Text(stringResource(R.string.parking_hold_hint),
            color = Brand.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HoldPad(stringResource(R.string.parking_forward), Icons.Filled.ArrowUpward, Modifier.weight(1f), onHold = { rpa.holdMove(true) }, onRelease = { rpa.releaseMove() })
            HoldPad(stringResource(R.string.parking_backward), Icons.Filled.ArrowDownward, Modifier.weight(1f), onHold = { rpa.holdMove(false) }, onRelease = { rpa.releaseMove() })
        }
    }
}

@Composable
private fun PhasePill(phase: RpaController.Phase, connecting: Boolean) {
    val (label, color) = rpaPhaseStyle(phase)
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (connecting) CircularProgressIndicator(Modifier.size(11.dp), color = color, strokeWidth = 1.5.dp)
        else Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Tile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Brand.surface2).clickable(onClick = onClick).padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(Brand.surface2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Brand.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun HoldPad(label: String, icon: ImageVector, modifier: Modifier, onHold: () -> Unit, onRelease: () -> Unit) {
    Column(
        modifier.height(100.dp).clip(RoundedCornerShape(16.dp)).background(Brand.accent.copy(alpha = 0.14f))
            .pointerInput(Unit) { detectTapGestures(onPress = { onHold(); tryAwaitRelease(); onRelease() }) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Brand.accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Brand.accent)
        }
        Text(label, color = Brand.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}
