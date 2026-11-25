package com.openzeekr.wear

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.openzeekr.app.wear.WearCarImage
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

private const val TAG = "OZWearCar"

/**
 * The picture of the user's car, as the phone renders it for the watch (the same rendered car, in its paint,
 * as on the phone's home screen), plus the charging outline that goes over it.
 *
 * The last picture received is kept in the app's private files, so the car is there at once on the next
 * launch and when the phone is out of reach. The phone is asked again each time the watch app comes to the
 * front, with the id of the stored picture; it only sends bytes when model, paint or picture version changed.
 */
object WearCarStore {
    class Picture(val id: String, val car: ImageBitmap, val neon: ImageBitmap?)

    val picture = MutableStateFlow<Picture?>(null)

    @Volatile private var loaded = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "car")

    /** Read the stored picture, once. Call off the main thread. */
    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            runCatching {
                val d = dir(ctx)
                val id = File(d, "id.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
                val car = File(d, "car.webp").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                if (id.isNotEmpty() && car != null) {
                    val neon = File(d, "neon.webp").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
                    picture.value = Picture(id, car.asImageBitmap(), neon?.asImageBitmap())
                }
            }.onFailure { Log.w(TAG, "stored car picture unreadable", it) }
        }
    }

    /** The id to send with a request: what is on screen now, blank when there is nothing. */
    fun knownId(): String = picture.value?.id.orEmpty()

    /** A [WearCarImage] arrived from the phone. Runs on the listener service's thread. */
    fun onImage(ctx: Context, data: ByteArray) {
        val img = WearCarImage.decode(data) ?: return
        runCatching {
            val d = dir(ctx)
            when {
                // The phone has no picture for the car that is active now: stop showing the old one.
                img.id.isEmpty() -> { d.deleteRecursively(); picture.value = null }
                // Same picture as the one already stored: nothing was sent, nothing to do.
                img.car == null -> Unit
                else -> {
                    val carBytes = img.car ?: return
                    val neonBytes = img.neon
                    val car = BitmapFactory.decodeByteArray(carBytes, 0, carBytes.size) ?: return
                    val neon = neonBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    d.mkdirs()
                    File(d, "id.txt").delete()               // no id while the files are being replaced
                    File(d, "car.webp").writeBytes(carBytes)
                    File(d, "neon.webp").let { f -> if (neonBytes != null) f.writeBytes(neonBytes) else f.delete() }
                    File(d, "id.txt").writeText(img.id)
                    loaded = true
                    picture.value = Picture(img.id, car.asImageBitmap(), neon?.asImageBitmap())
                }
            }
        }.onFailure { Log.w(TAG, "car picture not stored", it) }
    }
}
