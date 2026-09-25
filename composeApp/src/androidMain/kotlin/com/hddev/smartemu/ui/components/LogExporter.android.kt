package com.hddev.smartemu.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "LogExporter"

/** Shared reports kept in the logs directory; older ones are deleted. */
private const val KEPT_REPORTS = 10

@Composable
actual fun rememberLogExporter(): LogExporter {
    val context = LocalContext.current
    // The report waiting for the user to pick where to save it
    var pendingSave by remember { mutableStateOf<String?>(null) }
    val onDocumentCreated: (Uri?) -> Unit = { uri ->
        val text = pendingSave
        pendingSave = null
        if (uri != null && text != null) writeDocument(context, uri, text)
    }
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), onDocumentCreated)
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), onDocumentCreated)

    return remember(context, saveText, saveJson) {
        object : LogExporter {
            override fun copy(label: String, text: String): Boolean {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                return try {
                    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                    true
                } catch (e: RuntimeException) {
                    // A clip over the binder transaction limit is refused
                    Log.w(TAG, "Couldn't copy ${text.length} characters", e)
                    false
                }
            }

            override fun share(fileName: String, mimeType: String, text: String) {
                val file = writeReport(context, fileName, text)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, fileName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            override fun save(fileName: String, mimeType: String, text: String) {
                pendingSave = text
                if (mimeType == "application/json") saveJson.launch(fileName) else saveText.launch(fileName)
            }
        }
    }
}

/**
 * Writes a report to the app's external files, where `adb pull /sdcard/Android/data/<package>/files/logs` finds it,
 * or to its cache when there is no external storage.
 */
private fun writeReport(context: Context, fileName: String, text: String): File {
    val directory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "logs").apply { mkdirs() }
    directory.listFiles()
        ?.sortedByDescending { it.lastModified() }
        ?.drop(KEPT_REPORTS - 1)
        ?.forEach { it.delete() }
    return File(directory, fileName).apply { writeText(text) }.also { Log.i(TAG, "Wrote ${it.absolutePath}") }
}

private fun writeDocument(context: Context, uri: Uri, text: String) {
    val saved = try {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't save the log to $uri", e)
        false
    }
    Toast.makeText(context, if (saved) "Log saved" else "Couldn't save the log", Toast.LENGTH_SHORT).show()
}

@Composable
actual fun rememberPlatformDiagnostics(): Map<String, String> {
    val context = LocalContext.current
    return remember(context) {
        val packageManager = context.packageManager
        val version = try {
            packageManager.getPackageInfo(context.packageName, 0).let { info ->
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                "${info.versionName} ($code)"
            }
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
        mapOf(
            "App" to "${context.packageName} $version",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "HCE supported" to packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION).toString(),
            "NFC enabled" to (NfcAdapter.getDefaultAdapter(context)?.isEnabled?.toString() ?: "no adapter")
        )
    }
}
