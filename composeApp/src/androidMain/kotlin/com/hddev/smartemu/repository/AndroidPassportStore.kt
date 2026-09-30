package com.hddev.smartemu.repository

import android.content.Context
import android.util.Base64
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.ChipFault
import com.hddev.smartemu.data.DocumentType
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.Portrait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * Saves the passport in the app's private shared preferences, the portrait JPEG as Base64 alongside the fields.
 * A field that can't be read back, say an enum constant since renamed, falls back to its default.
 */
class AndroidPassportStore(context: Context) : PassportStore {

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): Result<PassportData?> = withContext(Dispatchers.IO) {
        runCatching {
            if (!preferences.contains(KEY_VERSION)) return@runCatching null

            val defaults = PassportData.empty()
            PassportData(
                passportNumber = preferences.getString(KEY_PASSPORT_NUMBER, null) ?: defaults.passportNumber,
                dateOfBirth = preferences.getString(KEY_DATE_OF_BIRTH, null)?.toLocalDateOrNull(),
                expiryDate = preferences.getString(KEY_EXPIRY_DATE, null)?.toLocalDateOrNull(),
                issuingCountry = preferences.getString(KEY_ISSUING_COUNTRY, null) ?: defaults.issuingCountry,
                nationality = preferences.getString(KEY_NATIONALITY, null) ?: defaults.nationality,
                firstName = preferences.getString(KEY_FIRST_NAME, null) ?: defaults.firstName,
                lastName = preferences.getString(KEY_LAST_NAME, null) ?: defaults.lastName,
                gender = preferences.getString(KEY_GENDER, null) ?: defaults.gender,
                accessControl = preferences.getString(KEY_ACCESS_CONTROL, null)
                    ?.let { name -> AccessControl.entries.find { it.name == name } } ?: defaults.accessControl,
                paceMapping = preferences.getString(KEY_PACE_MAPPING, null)
                    ?.let { name -> PaceMapping.entries.find { it.name == name } } ?: defaults.paceMapping,
                can = preferences.getString(KEY_CAN, null) ?: defaults.can,
                portrait = loadPortrait(),
                documentType = preferences.getString(KEY_DOCUMENT_TYPE, null)
                    ?.let { name -> DocumentType.entries.find { it.name == name } } ?: defaults.documentType,
                activeAuthentication = preferences.getBoolean(KEY_ACTIVE_AUTHENTICATION, defaults.activeAuthentication),
                chipFault = preferences.getString(KEY_CHIP_FAULT, null)
                    ?.let { name -> ChipFault.entries.find { it.name == name } } ?: defaults.chipFault,
                // An id no profile has any more resolves to the generic profile
                chipProfileId = preferences.getString(KEY_CHIP_PROFILE, null) ?: defaults.chipProfileId,
                personalNumber = preferences.getString(KEY_PERSONAL_NUMBER, null) ?: defaults.personalNumber,
                placeOfBirth = preferences.getString(KEY_PLACE_OF_BIRTH, null) ?: defaults.placeOfBirth,
                issuingAuthority = preferences.getString(KEY_ISSUING_AUTHORITY, null) ?: defaults.issuingAuthority,
                dateOfIssue = preferences.getString(KEY_DATE_OF_ISSUE, null)?.toLocalDateOrNull()
            )
        }
    }

    override suspend fun save(passportData: PassportData): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val saved = preferences.edit().apply {
                putInt(KEY_VERSION, VERSION)
                putString(KEY_PASSPORT_NUMBER, passportData.passportNumber)
                putString(KEY_DATE_OF_BIRTH, passportData.dateOfBirth?.toString())
                putString(KEY_EXPIRY_DATE, passportData.expiryDate?.toString())
                putString(KEY_ISSUING_COUNTRY, passportData.issuingCountry)
                putString(KEY_NATIONALITY, passportData.nationality)
                putString(KEY_FIRST_NAME, passportData.firstName)
                putString(KEY_LAST_NAME, passportData.lastName)
                putString(KEY_GENDER, passportData.gender)
                putString(KEY_ACCESS_CONTROL, passportData.accessControl.name)
                putString(KEY_PACE_MAPPING, passportData.paceMapping.name)
                putString(KEY_CAN, passportData.can)
                putString(KEY_DOCUMENT_TYPE, passportData.documentType.name)
                putBoolean(KEY_ACTIVE_AUTHENTICATION, passportData.activeAuthentication)
                putString(KEY_CHIP_FAULT, passportData.chipFault.name)
                putString(KEY_CHIP_PROFILE, passportData.chipProfileId)
                putString(KEY_PERSONAL_NUMBER, passportData.personalNumber)
                putString(KEY_PLACE_OF_BIRTH, passportData.placeOfBirth)
                putString(KEY_ISSUING_AUTHORITY, passportData.issuingAuthority)
                putString(KEY_DATE_OF_ISSUE, passportData.dateOfIssue?.toString())

                val portrait = passportData.portrait
                if (portrait != null) {
                    putString(KEY_PORTRAIT_JPEG, Base64.encodeToString(portrait.jpeg, Base64.NO_WRAP))
                    putInt(KEY_PORTRAIT_WIDTH, portrait.width)
                    putInt(KEY_PORTRAIT_HEIGHT, portrait.height)
                } else {
                    remove(KEY_PORTRAIT_JPEG)
                    remove(KEY_PORTRAIT_WIDTH)
                    remove(KEY_PORTRAIT_HEIGHT)
                }
            }.commit()
            check(saved) { "Could not write the passport to storage" }
        }
    }

    private fun loadPortrait(): Portrait? {
        val jpeg = preferences.getString(KEY_PORTRAIT_JPEG, null) ?: return null
        return runCatching {
            Portrait(
                jpeg = Base64.decode(jpeg, Base64.NO_WRAP),
                width = preferences.getInt(KEY_PORTRAIT_WIDTH, Portrait.WIDTH),
                height = preferences.getInt(KEY_PORTRAIT_HEIGHT, Portrait.HEIGHT)
            )
        }.getOrNull()
    }

    private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

    private companion object {
        const val PREFERENCES_NAME = "passport"

        /** Bumped when a key changes meaning, so an old save can be migrated rather than misread. */
        const val VERSION = 1

        const val KEY_VERSION = "version"
        const val KEY_PASSPORT_NUMBER = "passport_number"
        const val KEY_DATE_OF_BIRTH = "date_of_birth"
        const val KEY_EXPIRY_DATE = "expiry_date"
        const val KEY_ISSUING_COUNTRY = "issuing_country"
        const val KEY_NATIONALITY = "nationality"
        const val KEY_FIRST_NAME = "first_name"
        const val KEY_LAST_NAME = "last_name"
        const val KEY_GENDER = "gender"
        const val KEY_ACCESS_CONTROL = "access_control"
        const val KEY_PACE_MAPPING = "pace_mapping"
        const val KEY_CAN = "can"
        const val KEY_DOCUMENT_TYPE = "document_type"
        const val KEY_ACTIVE_AUTHENTICATION = "active_authentication"
        const val KEY_CHIP_FAULT = "chip_fault"
        const val KEY_CHIP_PROFILE = "chip_profile"
        const val KEY_PERSONAL_NUMBER = "personal_number"
        const val KEY_PLACE_OF_BIRTH = "place_of_birth"
        const val KEY_ISSUING_AUTHORITY = "issuing_authority"
        const val KEY_DATE_OF_ISSUE = "date_of_issue"
        const val KEY_PORTRAIT_JPEG = "portrait_jpeg"
        const val KEY_PORTRAIT_WIDTH = "portrait_width"
        const val KEY_PORTRAIT_HEIGHT = "portrait_height"
    }
}
