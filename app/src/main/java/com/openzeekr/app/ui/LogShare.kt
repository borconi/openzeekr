package com.openzeekr.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.openzeekr.app.R
import com.openzeekr.app.util.LogCrypto
import com.openzeekr.app.util.Logx
import java.io.File

/**
 * Encrypt the current log and hand it to the system share sheet as a FILE, so a non-technical
 * tester can email/attach it. The file is written under cacheDir/exports/ (the path the app's
 * FileProvider already exposes) and shared read-only via a content:// URI. Unlike a clipboard copy
 * this has no length cap, so long logs stay intact (and therefore decryptable). Returns a short
 * status string for the UI. Shares NOTHING on any crypto/IO failure - never the plaintext log.
 *
 * Used from Settings (LogViewer) and from onboarding's login step, so a tester who can't reach
 * Settings (login fails before the app opens) can still send us a diagnostic log.
 */
fun shareEncryptedLog(ctx: Context): String {
    val blob = LogCrypto.encryptToBase64(Logx.dump())
        ?: return ctx.getString(R.string.ui_log_share_failed_nothing)
    return runCatching {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "openzeekr-log-$stamp.txt").apply { writeText(blob) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.ui_log_share_subject, stamp))
            putExtra(Intent.EXTRA_TEXT, ctx.getString(R.string.ui_log_share_body))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, ctx.getString(R.string.ui_log_share_chooser_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  // launched from a non-Activity context
        ctx.startActivity(chooser)
        ctx.getString(R.string.ui_log_share_ready)
    }.getOrElse { ctx.getString(R.string.ui_log_share_failed, it.message ?: "") }
}
