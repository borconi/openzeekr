package com.openzeekr.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.openzeekr.app.remote.Category
import com.openzeekr.app.remote.Command

/** Icon for a catalog command; falls back to a per-category default. */
fun iconFor(cmd: Command): ImageVector = when (cmd) {
    Command.UNLOCK, Command.TRUNK_UNLOCK, Command.LOCKER_OFF -> Icons.Filled.LockOpen
    Command.LOCK, Command.TRUNK_LOCK, Command.LOCKER_ON -> Icons.Filled.Lock
    Command.FRONT_TRUNK, Command.TRUNK_OPEN -> Icons.Filled.Inventory2
    Command.CHARGE_LID_OPEN, Command.CHARGE_LID_CLOSE -> Icons.Filled.EvStation
    Command.AC_ON, Command.AC_OFF, Command.CABIN_ON, Command.CABIN_OFF, Command.CLIMATE_ZAF -> Icons.Filled.Air
    Command.DEFROST_ON, Command.DEFROST_OFF -> Icons.Filled.AcUnit
    Command.SEAT_HEAT_ON, Command.SEAT_HEAT_OFF, Command.STEER_WHEEL_ON, Command.STEER_WHEEL_OFF -> Icons.Filled.Thermostat
    Command.FRAGRANCE_ON, Command.FRAGRANCE_OFF -> Icons.Filled.LocalFlorist
    Command.FRIDGE_ON, Command.FRIDGE_OFF -> Icons.Filled.Kitchen
    Command.ENGINE_START, Command.ENGINE_STOP -> Icons.Filled.PowerSettingsNew
    Command.WINDOW_OPEN, Command.WINDOW_CLOSE, Command.WINDOW_VENT -> Icons.Filled.ViewColumn
    Command.SUNROOF_OPEN, Command.SUNROOF_CLOSE -> Icons.Filled.WbSunny
    Command.SUNSHADE_OPEN, Command.SUNSHADE_CLOSE -> Icons.Filled.Layers
    Command.CHARGING_ON, Command.CHARGING_OFF, Command.SET_CHARGE_SOC,
    Command.BATTERY_PREHEAT_ON, Command.BATTERY_PREHEAT_OFF -> Icons.Filled.BatteryChargingFull
    Command.FLASH -> Icons.Filled.FlashOn
    Command.HONK -> Icons.Filled.VolumeUp
    Command.FLASH_HORN -> Icons.Filled.Campaign
    Command.SENTINEL_ON, Command.SENTINEL_OFF -> Icons.Filled.Shield
    Command.GLOVEBOX_LOCK, Command.GLOVEBOX_UNLOCK -> Icons.Filled.Inventory2
    Command.VISITOR_ON, Command.VISITOR_OFF -> Icons.Filled.DirectionsWalk
}

fun iconFor(category: Category): ImageVector = when (category) {
    Category.DOORS -> Icons.Filled.Lock
    Category.CLIMATE -> Icons.Filled.Air
    Category.WINDOWS -> Icons.Filled.ViewColumn
    Category.CHARGING -> Icons.Filled.BatteryChargingFull
    Category.SIGNAL -> Icons.Filled.Campaign
    Category.SECURITY -> Icons.Filled.Shield
    Category.COMFORT -> Icons.Filled.EventSeat
    Category.SYSTEM -> Icons.Filled.Bolt
}
