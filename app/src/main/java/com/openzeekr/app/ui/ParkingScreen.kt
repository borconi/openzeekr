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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openzeekr.app.Deps
import com.openzeekr.app.R
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.ble.rpa.RpaReq
import com.openzeekr.app.ui.theme.Brand

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParkingScreen(deps: Deps, modifier: Modifier = Modifier) {
    val state by deps.rpa.state.collectAsState()
    val cfg by deps.config.config.collectAsState()
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
            RemoteParkingControls(deps.rpa, state)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteParkingControls(rpa: RpaController, state: RpaController.UiState) {
    val phase = state.phase
    val connecting = phase == RpaController.Phase.CONNECTING
    val canConnect = phase == RpaController.Phase.IDLE || phase == RpaController.Phase.ERROR || phase == RpaController.Phase.DONE
    val active = phase != RpaController.Phase.IDLE && phase != RpaController.Phase.ERROR && phase != RpaController.Phase.DONE

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocalParking, null, tint = Brand.accent, modifier = Modifier.size(21.dp))
            }
            Text(stringResource(R.string.ui_park_remote_parking), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            PhasePill(phase, connecting)
        }
        state.message?.let {
            Text(it, color = if (phase == RpaController.Phase.ERROR) Brand.crit else Brand.muted, fontSize = 12.5.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (connecting) stringResource(R.string.ui_park_connecting) else stringResource(R.string.ui_park_connect), Modifier.weight(1f), enabled = canConnect) { rpa.begin() }
            GhostButton(stringResource(R.string.ui_park_stop), Modifier.weight(1f), enabled = active, tint = Brand.crit) { rpa.stop() }
        }

        // DEBUG: fire every maneuver opcode once (skips the REQ_MODE→SYNC gate) to learn if
        // any command downstream gets a non-0x100a response. RPA is absent from stock Android
        // (iOS/DK3.0/UWB-gated), so this is exploratory. ⚠️ Keep clear space around the car.
        GhostButton(stringResource(R.string.ui_park_blind_probe), Modifier.fillMaxWidth(), tint = Brand.energy) { rpa.blindProbe() }
        Text(stringResource(R.string.ui_park_blind_probe_warning),
            color = Brand.faint, fontSize = 11.sp)

        SectionHeader(stringResource(R.string.ui_park_section_park_in))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(stringResource(R.string.ui_park_tile_search), Modifier.weight(1f)) { rpa.searchSlot() }
            Tile(stringResource(R.string.ui_park_tile_park_in), Modifier.weight(1f)) { rpa.startParkIn() }
            Tile(stringResource(R.string.ui_park_tile_continue), Modifier.weight(1f)) { rpa.continueParking() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(stringResource(R.string.ui_park_tile_pause), Modifier.weight(1f)) { rpa.pause() }
            Tile(stringResource(R.string.ui_park_tile_undo), Modifier.weight(1f)) { rpa.undo() }
            Spacer(Modifier.weight(1f))
        }

        SectionHeader(stringResource(R.string.ui_park_section_park_out))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                stringResource(R.string.ui_park_maneuver_parallel_r) to RpaReq.PARALLEL_RIGHT_OUT,
                stringResource(R.string.ui_park_maneuver_parallel_l) to RpaReq.PARALLEL_LEFT_OUT,
                stringResource(R.string.ui_park_maneuver_head_perp_r) to RpaReq.HEAD_PERPENDICULAR_RIGHT_OUT,
                stringResource(R.string.ui_park_maneuver_head_perp_l) to RpaReq.HEAD_PERPENDICULAR_LEFT_OUT,
                stringResource(R.string.ui_park_maneuver_tail_perp_r) to RpaReq.TAIL_PERPENDICULAR_RIGHT_OUT,
                stringResource(R.string.ui_park_maneuver_tail_perp_l) to RpaReq.TAIL_PERPENDICULAR_LEFT_OUT,
            ).forEach { (label, dir) -> Chip(label) { rpa.startParkOut(dir) } }
        }

        SectionHeader(stringResource(R.string.ui_park_section_hold_to_move))
        Text(stringResource(R.string.ui_park_hold_to_move_hint),
            color = Brand.muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HoldPad(stringResource(R.string.ui_park_move_forward), Icons.Filled.ArrowUpward, Modifier.weight(1f), onHold = { rpa.holdMove(true) }, onRelease = { rpa.releaseMove() })
            HoldPad(stringResource(R.string.ui_park_move_backward), Icons.Filled.ArrowDownward, Modifier.weight(1f), onHold = { rpa.holdMove(false) }, onRelease = { rpa.releaseMove() })
        }
    }
}

@Composable
private fun PhasePill(phase: RpaController.Phase, connecting: Boolean) {
    val (label, color) = when (phase) {
        RpaController.Phase.READY -> stringResource(R.string.ui_park_phase_ready) to Brand.good
        RpaController.Phase.MOVING, RpaController.Phase.PARKING_IN, RpaController.Phase.PARKING_OUT -> stringResource(R.string.ui_park_phase_active) to Brand.accent
        RpaController.Phase.PAUSED -> stringResource(R.string.ui_park_phase_paused) to Brand.energy
        RpaController.Phase.DONE -> stringResource(R.string.ui_park_phase_done) to Brand.good
        RpaController.Phase.ERROR -> stringResource(R.string.ui_park_phase_error) to Brand.crit
        RpaController.Phase.CONNECTING -> stringResource(R.string.ui_park_phase_connecting) to Brand.accent
        else -> stringResource(R.string.ui_park_phase_idle) to Brand.faint
    }
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
