package com.hddev.smartemu.capture.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.hddev.smartemu.capture.CaptureJson
import com.hddev.smartemu.capture.CaptureRecord
import java.io.File

/** What the capture screen needs of storage, so that its logic can be tested without Android. */
interface CaptureStorage {
    fun save(record: CaptureRecord): File
    fun delete(record: CaptureRecord)
    fun savedCount(): Int
    fun deleteAll()
    fun shareJson(record: CaptureRecord)
    fun shareReport(record: CaptureRecord, text: String)
}

/**
 * Where a capture goes once the user chooses to keep or share it, and nowhere before: a capture lives in memory
 * until then. A capture the user saves goes to `filesDir/captures`, which the backup rules exclude
 * (res/xml/backup_rules.xml and data_extraction_rules.xml). Sharing writes to `cacheDir/capture-reports` instead, never
 * backed up and cleared by the system under storage pressure, and does not save: only an explicit [save] keeps a
 * capture in `filesDir`.
 *
 * Retention: on creation, saved captures and shared files older than [RETENTION_DAYS] days are deleted
 * ([purgeOlderThan]); the one-time cost is a directory listing. The user can also delete them all from the capture screen.
 */
class CaptureStore(private val context: Context, now: () -> Long = System::currentTimeMillis) : CaptureStorage {

    private val directory get() = File(context.filesDir, DIRECTORY)
    private val reportDirectory get() = File(context.cacheDir, REPORT_DIRECTORY)

    init {
        val cutoff = now() - RETENTION_DAYS * DAY_MS
        purgeOlderThan(directory, cutoff)
        purgeOlderThan(reportDirectory, cutoff)
    }

    /** Writes [record] as JSON and returns the file. */
    override fun save(record: CaptureRecord): File {
        val file = File(directory.apply { mkdirs() }, fileName(record))
        file.writeText(CaptureJson.encode(record))
        return file
    }

    /** Deletes the file of a capture, and the files written for sharing it. */
    override fun delete(record: CaptureRecord) {
        File(directory, fileName(record)).delete()
        File(reportDirectory, reportName(record)).delete()
        File(reportDirectory, fileName(record)).delete()
    }

    override fun savedCount(): Int = directory.listFiles()?.count { it.isFile } ?: 0

    override fun deleteAll() {
        directory.listFiles()?.forEach { it.delete() }
        reportDirectory.listFiles()?.forEach { it.delete() }
    }

    /** Offers the capture as `application/json` through the share sheet, from a file in the cache; nothing is saved. */
    override fun shareJson(record: CaptureRecord) {
        val file = File(reportDirectory.apply { mkdirs() }, fileName(record))
        file.writeText(CaptureJson.encode(record))
        share(file, "application/json", file.name)
    }

    /** Offers the one-page report as text through the share sheet. */
    override fun shareReport(record: CaptureRecord, text: String) {
        val file = File(reportDirectory.apply { mkdirs() }, reportName(record))
        file.writeText(text)
        share(file, "text/plain", file.name)
    }

    private fun share(file: File, mimeType: String, subject: String) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share capture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val DIRECTORY = "captures"
        const val REPORT_DIRECTORY = "capture-reports"

        /** A saved capture or shared file older than this is deleted the next time the store is created. */
        const val RETENTION_DAYS = 30L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Deletes the files of [directory] last modified before [cutoffMillis]; returns how many it deleted. */
        fun purgeOlderThan(directory: File, cutoffMillis: Long): Int =
            directory.listFiles()?.count { it.isFile && it.lastModified() < cutoffMillis && it.delete() } ?: 0

        // The id is random, but it is the one part of a file name that isn't ours, so keep it to safe characters
        private fun safeId(record: CaptureRecord) = record.captureId.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "capture" }
        private fun fileName(record: CaptureRecord) = "capture-${safeId(record)}.json"
        private fun reportName(record: CaptureRecord) = "capture-report-${safeId(record)}.txt"
    }
}
