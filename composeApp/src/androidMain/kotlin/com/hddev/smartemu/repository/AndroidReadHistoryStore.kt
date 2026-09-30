package com.hddev.smartemu.repository

import android.content.Context
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.DocumentType
import com.hddev.smartemu.data.ReadOutcome
import com.hddev.smartemu.data.ReadRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Instant

/**
 * Saves the read history in the app's private shared preferences, as a JSON array. A record that can't be read
 * back, say one with an enum constant since renamed, is left out.
 */
class AndroidReadHistoryStore(context: Context) : ReadHistoryStore {

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): Result<List<ReadRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray(preferences.getString(KEY_RECORDS, null) ?: return@runCatching emptyList())
            (0 until array.length()).mapNotNull { index -> runCatching { array.getJSONObject(index).toRecord() }.getOrNull() }
        }
    }

    override suspend fun save(records: List<ReadRecord>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray().apply { records.forEach { put(it.toJson()) } }
            check(preferences.edit().putString(KEY_RECORDS, array.toString()).commit()) {
                "Could not write the read history to storage"
            }
        }
    }

    private fun ReadRecord.toJson() = JSONObject().apply {
        put("startedAt", startedAt.toString())
        put("endedAt", endedAt.toString())
        put("documentType", documentType.name)
        put("holder", holder)
        put("documentNumber", documentNumber)
        accessProtocol?.let { put("accessProtocol", it) }
        put("filesRead", JSONArray(filesRead))
        put("activeAuthentication", activeAuthentication)
        put("chipFault", chipFault.name)
        put("outcome", outcome.name)
        failureReason?.let { put("failureReason", it) }
    }

    private fun JSONObject.toRecord() = ReadRecord(
        startedAt = Instant.parse(getString("startedAt")),
        endedAt = Instant.parse(getString("endedAt")),
        documentType = DocumentType.valueOf(getString("documentType")),
        holder = getString("holder"),
        documentNumber = getString("documentNumber"),
        accessProtocol = optString("accessProtocol").ifEmpty { null },
        filesRead = getJSONArray("filesRead").let { files -> List(files.length()) { files.getString(it) } },
        activeAuthentication = getBoolean("activeAuthentication"),
        chipFault = ChipFault.valueOf(getString("chipFault")),
        outcome = ReadOutcome.valueOf(getString("outcome")),
        failureReason = optString("failureReason").ifEmpty { null }
    )

    private companion object {
        const val PREFERENCES_NAME = "read_history"
        const val KEY_RECORDS = "records"
    }
}
