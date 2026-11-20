package com.openzeekr.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Sleek, thin-stroke (1.6dp) automotive line-art icons matching the Zeekr Signature aesthetic.
 * Tinted by the caller's [androidx.compose.material3.Icon] (stroke colour here is a placeholder).
 */
object OzIcons {

    private val Ink = SolidColor(Color(0xFF000000))
    private const val STROKE = 1.6f

    private fun lineIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private fun ImageVector.Builder.strokePath(block: PathBuilder.() -> Unit) {
        path(
            stroke = Ink,
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    /** Car side window with A-pillar and door sill. */
    val CarWindow: ImageVector by lazy {
        lineIcon("CarWindow") {
            strokePath {
                moveTo(4f, 6.5f)
                lineTo(20f, 6.5f)
                lineTo(20f, 17.5f)
                lineTo(4f, 17.5f)
                close()
            }
            strokePath {
                moveTo(5.5f, 13.2f)
                lineTo(18.5f, 13.2f)
            }
            strokePath {
                moveTo(9f, 6.5f)
                lineTo(7.2f, 13.2f)
            }
            strokePath {
                moveTo(14.5f, 15.4f)
                lineTo(16.8f, 15.4f)
            }
        }
    }

    /** Minimalist closed padlock. */
    val LockClosed: ImageVector by lazy {
        lineIcon("LockClosed") {
            strokePath {
                moveTo(6f, 10.5f)
                lineTo(18f, 10.5f)
                quadTo(19f, 10.5f, 19f, 11.5f)
                lineTo(19f, 19f)
                quadTo(19f, 20.5f, 17.5f, 20.5f)
                lineTo(6.5f, 20.5f)
                quadTo(5f, 20.5f, 5f, 19f)
                lineTo(5f, 11.5f)
                quadTo(5f, 10.5f, 6f, 10.5f)
                close()
            }
            strokePath {
                moveTo(8.5f, 10.5f)
                lineTo(8.5f, 7.2f)
                arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 7.2f)
                lineTo(15.5f, 10.5f)
            }
            strokePath {
                moveTo(12f, 14.5f)
                lineTo(12f, 16.8f)
            }
        }
    }

    /** Minimalist open padlock. */
    val LockOpen: ImageVector by lazy {
        lineIcon("LockOpen") {
            strokePath {
                moveTo(6f, 10.5f)
                lineTo(18f, 10.5f)
                quadTo(19f, 10.5f, 19f, 11.5f)
                lineTo(19f, 19f)
                quadTo(19f, 20.5f, 17.5f, 20.5f)
                lineTo(6.5f, 20.5f)
                quadTo(5f, 20.5f, 5f, 19f)
                lineTo(5f, 11.5f)
                quadTo(5f, 10.5f, 6f, 10.5f)
                close()
            }
            strokePath {
                moveTo(8.5f, 10.5f)
                lineTo(8.5f, 7f)
                arcTo(3.5f, 3.5f, 0f, false, true, 15.2f, 5.5f)
            }
            strokePath {
                moveTo(12f, 14.5f)
                lineTo(12f, 16.8f)
            }
        }
    }

    /** Sleek 4-blade climate turbine fan. */
    val ClimateFan: ImageVector by lazy {
        lineIcon("ClimateFan") {
            // Center hub
            strokePath {
                moveTo(13.6f, 12f)
                arcTo(1.6f, 1.6f, 0f, true, true, 10.4f, 12f)
                arcTo(1.6f, 1.6f, 0f, true, true, 13.6f, 12f)
            }
            // Top blade
            strokePath {
                moveTo(12f, 10.4f)
                curveTo(12f, 6.5f, 15.5f, 4.2f, 16.5f, 6.5f)
                curveTo(17.2f, 8.2f, 14.5f, 10.2f, 13.4f, 11f)
            }
            // Right blade
            strokePath {
                moveTo(13.6f, 12f)
                curveTo(17.5f, 12f, 19.8f, 15.5f, 17.5f, 16.5f)
                curveTo(15.8f, 17.2f, 13.8f, 14.5f, 13f, 13.4f)
            }
            // Bottom blade
            strokePath {
                moveTo(12f, 13.6f)
                curveTo(12f, 17.5f, 8.5f, 19.8f, 7.5f, 17.5f)
                curveTo(6.8f, 15.8f, 9.5f, 13.8f, 10.6f, 13f)
            }
            // Left blade
            strokePath {
                moveTo(10.4f, 12f)
                curveTo(6.5f, 12f, 4.2f, 8.5f, 6.5f, 7.5f)
                curveTo(8.2f, 6.8f, 10.2f, 9.5f, 11f, 10.6f)
            }
        }
    }

    /** SUV profile with open rear tailgate (Trunk). */
    val TrunkOpen: ImageVector by lazy {
        lineIcon("TrunkOpen") {
            // Car body + wheels
            strokePath {
                moveTo(3f, 14.5f)
                lineTo(3.8f, 11.2f)
                lineTo(7.5f, 8.8f)
                lineTo(13.5f, 8.8f)
                lineTo(16f, 11.2f)
            }
            // Raised tailgate
            strokePath {
                moveTo(13.5f, 8.8f)
                lineTo(18.8f, 5.5f)
            }
            // Lower chassis line
            strokePath {
                moveTo(3f, 14.5f)
                lineTo(4.8f, 14.5f)
            }
            strokePath {
                moveTo(8.6f, 14.5f)
                lineTo(14.8f, 14.5f)
            }
            strokePath {
                moveTo(18.6f, 14.5f)
                lineTo(20.2f, 14.5f)
                lineTo(19.8f, 11.5f)
                lineTo(16f, 11.2f)
            }
            // Front wheel
            strokePath {
                moveTo(8.5f, 14.8f)
                arcTo(1.8f, 1.8f, 0f, true, true, 4.9f, 14.8f)
                arcTo(1.8f, 1.8f, 0f, true, true, 8.5f, 14.8f)
            }
            // Rear wheel
            strokePath {
                moveTo(18.5f, 14.8f)
                arcTo(1.8f, 1.8f, 0f, true, true, 14.9f, 14.8f)
                arcTo(1.8f, 1.8f, 0f, true, true, 18.5f, 14.8f)
            }
        }
    }

    /** SUV profile with open front hood (Frunk). */
    val Frunk: ImageVector by lazy {
        lineIcon("Frunk") {
            strokePath {
                moveTo(5f, 5.5f)
                lineTo(10.2f, 8.8f)
                lineTo(16.5f, 8.8f)
                lineTo(20.2f, 11.2f)
                lineTo(21f, 14.5f)
                lineTo(19f, 14.5f)
            }
            strokePath {
                moveTo(10.2f, 8.8f)
                lineTo(7.8f, 11.2f)
                lineTo(4f, 11.5f)
                lineTo(3.6f, 14.5f)
                lineTo(5.2f, 14.5f)
            }
            strokePath {
                moveTo(9f, 14.5f)
                lineTo(15.2f, 14.5f)
            }
            strokePath {
                moveTo(9f, 14.8f)
                arcTo(1.8f, 1.8f, 0f, true, true, 5.4f, 14.8f)
                arcTo(1.8f, 1.8f, 0f, true, true, 9f, 14.8f)
            }
            strokePath {
                moveTo(19f, 14.8f)
                arcTo(1.8f, 1.8f, 0f, true, true, 15.4f, 14.8f)
                arcTo(1.8f, 1.8f, 0f, true, true, 19f, 14.8f)
            }
        }
    }

    /** EV charging pillar with cable & lightning bolt. */
    val ChargeStation: ImageVector by lazy {
        lineIcon("ChargeStation") {
            // Pillar
            strokePath {
                moveTo(6f, 19.5f)
                lineTo(6f, 5.5f)
                quadTo(6f, 4.5f, 7f, 4.5f)
                lineTo(13.5f, 4.5f)
                quadTo(14.5f, 4.5f, 14.5f, 5.5f)
                lineTo(14.5f, 19.5f)
            }
            // Base
            strokePath {
                moveTo(4.5f, 19.5f)
                lineTo(16f, 19.5f)
            }
            // Bolt inside pillar
            strokePath {
                moveTo(10.8f, 7.8f)
                lineTo(8.8f, 11.2f)
                lineTo(10.6f, 11.2f)
                lineTo(9.8f, 14.5f)
                lineTo(12f, 10.8f)
                lineTo(10.2f, 10.8f)
                close()
            }
            // Cable + plug
            strokePath {
                moveTo(14.5f, 11f)
                lineTo(16.5f, 11f)
                quadTo(18f, 11f, 18f, 12.5f)
                lineTo(18f, 16.5f)
                quadTo(18f, 18f, 19.2f, 18f)
                lineTo(19.5f, 18f)
                lineTo(19.5f, 9f)
            }
        }
    }

    /** Minimalist split-door console mini-fridge. */
    val Fridge: ImageVector by lazy {
        lineIcon("Fridge") {
            strokePath {
                moveTo(7.5f, 4f)
                lineTo(16.5f, 4f)
                quadTo(17.5f, 4f, 17.5f, 5f)
                lineTo(17.5f, 19f)
                quadTo(17.5f, 20f, 16.5f, 20f)
                lineTo(7.5f, 20f)
                quadTo(6.5f, 20f, 6.5f, 19f)
                lineTo(6.5f, 5f)
                quadTo(6.5f, 4f, 7.5f, 4f)
                close()
            }
            // Compartment divider
            strokePath {
                moveTo(6.5f, 10f)
                lineTo(17.5f, 10f)
            }
            // Handles
            strokePath {
                moveTo(9f, 6.5f)
                lineTo(9f, 8.2f)
            }
            strokePath {
                moveTo(9f, 12.2f)
                lineTo(9f, 14.8f)
            }
        }
    }

    /** Automotive headlight with 3 light beams. */
    val Headlights: ImageVector by lazy {
        lineIcon("Headlights") {
            strokePath {
                moveTo(11f, 6.5f)
                curveTo(6.5f, 6.5f, 4f, 9f, 4f, 12f)
                curveTo(4f, 15f, 6.5f, 17.5f, 11f, 17.5f)
                curveTo(12.2f, 15.8f, 12.2f, 8.2f, 11f, 6.5f)
                close()
            }
            strokePath {
                moveTo(14.5f, 8f)
                lineTo(19.8f, 6.8f)
            }
            strokePath {
                moveTo(15f, 12f)
                lineTo(20.2f, 12f)
            }
            strokePath {
                moveTo(14.5f, 16f)
                lineTo(19.8f, 17.2f)
            }
        }
    }

    /** Sleek automotive horn. */
    val Horn: ImageVector by lazy {
        lineIcon("Horn") {
            strokePath {
                moveTo(4f, 10.5f)
                lineTo(8f, 10.5f)
                lineTo(16.5f, 6.5f)
                lineTo(16.5f, 17.5f)
                lineTo(8f, 13.5f)
                lineTo(4f, 13.5f)
                close()
            }
            strokePath {
                moveTo(19.2f, 9.2f)
                curveTo(20.4f, 10.8f, 20.4f, 13.2f, 19.2f, 14.8f)
            }
        }
    }

    // --- Bottom Navigation Line-Art Icons ---

    /** Front-view car silhouette for Vehicle tab. */
    val NavCar: ImageVector by lazy {
        lineIcon("NavCar") {
            strokePath {
                moveTo(5f, 11.5f)
                lineTo(6.8f, 6.8f)
                quadTo(7.2f, 6f, 8.2f, 6f)
                lineTo(15.8f, 6f)
                quadTo(16.8f, 6f, 17.2f, 6.8f)
                lineTo(19f, 11.5f)
                lineTo(19.5f, 16.5f)
                lineTo(4.5f, 16.5f)
                close()
            }
            strokePath {
                moveTo(5.5f, 16.5f)
                lineTo(5.5f, 18.5f)
            }
            strokePath {
                moveTo(18.5f, 16.5f)
                lineTo(18.5f, 18.5f)
            }
            strokePath {
                moveTo(7f, 13.5f)
                lineTo(8.8f, 13.5f)
            }
            strokePath {
                moveTo(15.2f, 13.5f)
                lineTo(17f, 13.5f)
            }
        }
    }

    /** Minimalist location pin for Parking tab. */
    val NavPin: ImageVector by lazy {
        lineIcon("NavPin") {
            strokePath {
                moveTo(12f, 20.5f)
                curveTo(12f, 20.5f, 18f, 14.5f, 18f, 10f)
                arcTo(6f, 6f, 0f, true, false, 6f, 10f)
                curveTo(6f, 14.5f, 12f, 20.5f, 12f, 20.5f)
                close()
            }
            strokePath {
                moveTo(14.2f, 10f)
                arcTo(2.2f, 2.2f, 0f, true, true, 9.8f, 10f)
                arcTo(2.2f, 2.2f, 0f, true, true, 14.2f, 10f)
            }
        }
    }

    /** Minimalist shield with check for Security tab. */
    val NavShield: ImageVector by lazy {
        lineIcon("NavShield") {
            strokePath {
                moveTo(12f, 3.8f)
                lineTo(18.5f, 6.2f)
                lineTo(18.5f, 11.5f)
                curveTo(18.5f, 15.8f, 15.6f, 19.2f, 12f, 20.5f)
                curveTo(8.4f, 19.2f, 5.5f, 15.8f, 5.5f, 11.5f)
                lineTo(5.5f, 6.2f)
                close()
            }
            strokePath {
                moveTo(9.2f, 12f)
                lineTo(11.2f, 14f)
                lineTo(15f, 9.8f)
            }
        }
    }

    /** Minimalist calendar for Schedule tab. */
    val NavSchedule: ImageVector by lazy {
        lineIcon("NavSchedule") {
            strokePath {
                moveTo(4.5f, 6.5f)
                lineTo(19.5f, 6.5f)
                lineTo(19.5f, 19.5f)
                lineTo(4.5f, 19.5f)
                close()
            }
            strokePath {
                moveTo(8f, 4.2f)
                lineTo(8f, 7.2f)
            }
            strokePath {
                moveTo(16f, 4.2f)
                lineTo(16f, 7.2f)
            }
            strokePath {
                moveTo(4.5f, 10.5f)
                lineTo(19.5f, 10.5f)
            }
        }
    }

    /** Minimalist digital key for Key tab. */
    val NavKey: ImageVector by lazy {
        lineIcon("NavKey") {
            strokePath {
                moveTo(11.5f, 12f)
                arcTo(3.8f, 3.8f, 0f, true, true, 3.9f, 12f)
                arcTo(3.8f, 3.8f, 0f, true, true, 11.5f, 12f)
            }
            strokePath {
                moveTo(11.5f, 12f)
                lineTo(20f, 12f)
                lineTo(20f, 15f)
            }
            strokePath {
                moveTo(16.8f, 12f)
                lineTo(16.8f, 14.5f)
            }
        }
    }

    /** Minimalist wallbox charger for Wallbox tab. */
    val NavWallbox: ImageVector by lazy {
        lineIcon("NavWallbox") {
            strokePath {
                moveTo(6.5f, 4.5f)
                lineTo(15.5f, 4.5f)
                quadTo(16.5f, 4.5f, 16.5f, 5.5f)
                lineTo(16.5f, 18.5f)
                quadTo(16.5f, 19.5f, 15.5f, 19.5f)
                lineTo(6.5f, 19.5f)
                quadTo(5.5f, 19.5f, 5.5f, 18.5f)
                lineTo(5.5f, 5.5f)
                quadTo(5.5f, 4.5f, 6.5f, 4.5f)
                close()
            }
            strokePath {
                moveTo(11.4f, 8.5f)
                lineTo(9.6f, 12f)
                lineTo(11.3f, 12f)
                lineTo(10.6f, 15.5f)
                lineTo(12.6f, 11.6f)
                lineTo(10.8f, 11.6f)
                close()
            }
            strokePath {
                moveTo(16.5f, 13f)
                lineTo(18.5f, 13f)
                quadTo(19.5f, 13f, 19.5f, 14f)
                lineTo(19.5f, 17.5f)
            }
        }
    }

    /** Minimalist download/update icon for Updates tab. */
    val NavUpdates: ImageVector by lazy {
        lineIcon("NavUpdates") {
            strokePath {
                moveTo(12f, 4.5f)
                lineTo(12f, 14.5f)
            }
            strokePath {
                moveTo(8.5f, 11f)
                lineTo(12f, 14.5f)
                lineTo(15.5f, 11f)
            }
            strokePath {
                moveTo(5f, 18.5f)
                lineTo(19f, 18.5f)
            }
        }
    }

    /** Minimalist thin-stroke gear for Settings tab. */
    val NavSettings: ImageVector by lazy {
        lineIcon("NavSettings") {
            strokePath {
                moveTo(14.8f, 12f)
                arcTo(2.8f, 2.8f, 0f, true, true, 9.2f, 12f)
                arcTo(2.8f, 2.8f, 0f, true, true, 14.8f, 12f)
            }
            strokePath {
                moveTo(12f, 4f)
                lineTo(12f, 6f)
            }
            strokePath {
                moveTo(12f, 18f)
                lineTo(12f, 20f)
            }
            strokePath {
                moveTo(4f, 12f)
                lineTo(6f, 12f)
            }
            strokePath {
                moveTo(18f, 12f)
                lineTo(20f, 12f)
            }
            strokePath {
                moveTo(6.3f, 6.3f)
                lineTo(7.8f, 7.8f)
            }
            strokePath {
                moveTo(16.2f, 16.2f)
                lineTo(17.7f, 17.7f)
            }
            strokePath {
                moveTo(17.7f, 6.3f)
                lineTo(16.2f, 7.8f)
            }
            strokePath {
                moveTo(7.8f, 16.2f)
                lineTo(6.3f, 17.7f)
            }
            strokePath {
                moveTo(18f, 12f)
                arcTo(6f, 6f, 0f, true, true, 6f, 12f)
                arcTo(6f, 6f, 0f, true, true, 18f, 12f)
            }
        }
    }
}
