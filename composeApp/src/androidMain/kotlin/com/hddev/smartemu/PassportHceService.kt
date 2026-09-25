package com.hddev.smartemu

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.domain.SimulatorError
import com.hddev.smartemu.utils.ApduParser
import com.hddev.smartemu.utils.BacProtocol
import com.hddev.smartemu.utils.ChipSecureMessaging
import com.hddev.smartemu.utils.PaceProtocol
import com.hddev.smartemu.utils.PassportLdsFiles
import com.hddev.smartemu.utils.ErrorLogger
import com.hddev.smartemu.utils.ErrorCodeMapper
import com.hddev.smartemu.utils.TimeoutHandler
import com.hddev.smartemu.utils.ErrorRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import net.sf.scuba.smartcards.CommandAPDU
import org.jmrtd.PassportService
import net.sf.scuba.smartcards.ResponseAPDU
import net.sf.scuba.smartcards.ISO7816
import java.util.UUID

/**
 * Host Card Emulation service for simulating passport NFC chip.
 * Emulates an ICAO 9303 LDS1 eMRTD protected by BAC, PACE or both, as chosen by [PassportData.accessControl]:
 * - PACE: EF.CardAccess in the master file (readable in plain), MSE:Set AT and GENERAL AUTHENTICATE, with the
 *   mapping chosen by [PassportData.paceMapping]; with CAM, EF.CardSecurity in the master file (read under PACE)
 * - BAC: GET CHALLENGE and EXTERNAL AUTHENTICATE
 * Either establishes secure messaging, under which the eMRTD application's EF.COM, EF.SOD, EF.DG1 and EF.DG2
 * can be selected and read.
 */
class PassportHceService : HostApduService() {
    
    companion object {
        private const val TAG = "PassportHceService"
        
        // Shared event flow for communication with the app. Emitted without suspending, so events keep their order
        // and a slow collector never holds up a reader; if it falls this far behind, the oldest events are dropped
        private val _nfcEvents = MutableSharedFlow<NfcEvent>(
            replay = 0,
            extraBufferCapacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val nfcEvents: SharedFlow<NfcEvent> = _nfcEvents.asSharedFlow()
        
        // Shared passport data for HCE service
        @Volatile
        private var sharedPassportData: PassportData? = null
        
        /**
         * Sets the passport data that will be used by the HCE service.
         * This method is called by the repository when starting simulation.
         */
        fun setSharedPassportData(passportData: PassportData?) {
            sharedPassportData = passportData
        }
        
        /**
         * Gets the current shared passport data.
         */
        fun getSharedPassportData(): PassportData? = sharedPassportData
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isConnected = false
    private var isApplicationSelected = false
    private var selectedFileId: Short? = null
    
    // Error handling and recovery
    private val timeoutHandler = TimeoutHandler()
    private val errorRecovery = ErrorRecovery()
    private var sessionCorrelationId: String? = null
    
    // SCUBA library components
    private var isScubaInitialized = false
    
    // Access control protocol handlers
    private val bacProtocol = BacProtocol()
    private val paceProtocol = PaceProtocol()
    
    // Secure messaging session established by the last successful BAC or PACE run
    private var secureMessaging: ChipSecureMessaging? = null
    
    // The unwrapped command and unprotected response of the secure messaging exchange in progress, for the APDU trace
    private var plainExchange: Pair<ByteArray, ByteArray>? = null
    
    // Passport data and the LDS files generated from it for the current simulation
    private var currentPassportData: PassportData? = null
    private var ldsFiles: PassportLdsFiles? = null
    
    // Largest READ BINARY payload returned, so the secure messaging response still fits a short APDU
    private val maxReadLength = PassportService.DEFAULT_MAX_BLOCKSIZE
    
    private val MASTER_FILE_ID: Short = 0x3F00
    
    override fun onCreate() {
        super.onCreate()
        sessionCorrelationId = UUID.randomUUID().toString()
        
        Log.d(TAG, "PassportHceService created with session ID: $sessionCorrelationId")
        
        ErrorLogger.logError(
            level = ErrorLogger.LogLevel.INFO,
            category = ErrorLogger.ErrorCategory.SYSTEM,
            message = "PassportHceService created",
            context = mapOf("sessionId" to sessionCorrelationId!!),
            correlationId = sessionCorrelationId
        )
        
        initializeScubaLibrary()
        
        emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "Service initialized"))
    }
    
    /**
     * Initialize SCUBA library components for smart card operations.
     */
    private fun initializeScubaLibrary() {
        runBlocking {
            val result = errorRecovery.withRecovery(
                operation = "SCUBA_INITIALIZATION",
                config = ErrorRecovery.RecoveryConfig(
                    maxRetries = 2,
                    correlationId = sessionCorrelationId
                )
            ) { attemptNumber ->
                Log.d(TAG, "Initializing SCUBA library (attempt $attemptNumber)")
                
                Log.d(TAG, "SCUBA library initialized successfully")
                true
            }
            
            when (result) {
                is ErrorRecovery.RecoveryResult.Success -> {
                    isScubaInitialized = true
                    ErrorLogger.logError(
                        level = ErrorLogger.LogLevel.INFO,
                        category = ErrorLogger.ErrorCategory.SYSTEM,
                        message = "SCUBA library initialized successfully",
                        correlationId = sessionCorrelationId
                    )
                    emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "SCUBA library initialized"))
                }
                
                is ErrorRecovery.RecoveryResult.Failed -> {
                    isScubaInitialized = false
                    val error = SimulatorError.SystemError.LibraryInitializationFailed("SCUBA", Exception(result.lastError.message))
                    
                    ErrorLogger.logError(
                        level = ErrorLogger.LogLevel.CRITICAL,
                        category = ErrorLogger.ErrorCategory.SYSTEM,
                        message = "Failed to initialize SCUBA library after ${result.totalAttempts} attempts",
                        error = error,
                        correlationId = sessionCorrelationId
                    )
                    
                    val errorResponse = ErrorCodeMapper.mapError(error)
                    emitEvent(NfcEvent.error(Clock.System.now(), errorResponse.message, errorResponse.errorCode))
                }
                
                is ErrorRecovery.RecoveryResult.NonRetryable -> {
                    isScubaInitialized = false
                    ErrorLogger.logError(
                        level = ErrorLogger.LogLevel.CRITICAL,
                        category = ErrorLogger.ErrorCategory.SYSTEM,
                        message = "SCUBA initialization failed with non-retryable error",
                        error = result.error,
                        correlationId = sessionCorrelationId
                    )
                    
                    val errorResponse = ErrorCodeMapper.mapError(result.error)
                    emitEvent(NfcEvent.error(Clock.System.now(), errorResponse.message, errorResponse.errorCode))
                }
            }
        }
    }
    
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        plainExchange = null
        val response = respondToCommandApdu(commandApdu)
        emitEvent(apduTraceEvent(commandApdu, response, plainExchange))
        plainExchange = null
        return response
    }
    
    /**
     * Summarises an exchange as, for example, "READ BINARY → 9000", naming the unwrapped command under secure messaging.
     */
    private fun apduTraceEvent(
        commandApdu: ByteArray?,
        response: ByteArray,
        plainExchange: Pair<ByteArray, ByteArray>?
    ): NfcEvent {
        val hex: (ByteArray) -> String = { ApduParser.run { it.toHexString() } }
        val shownCommand = plainExchange?.first ?: commandApdu
        val shownResponse = plainExchange?.second ?: response
        val name = shownCommand?.takeIf { it.size >= 2 }?.let { instructionName(it[1]) } ?: "Malformed command"
        val statusWord = if (shownResponse.size >= 2) hex(shownResponse.copyOfRange(shownResponse.size - 2, shownResponse.size)) else "none"
        val protection = if (plainExchange != null) " (SM)" else ""
        return NfcEvent.apdu(
            timestamp = Clock.System.now(),
            summary = "$name$protection → $statusWord",
            status = statusWord,
            command = commandApdu?.let(hex) ?: "null",
            response = hex(response),
            plainCommand = plainExchange?.first?.let(hex),
            plainResponse = plainExchange?.second?.let(hex)
        )
    }
    
    private fun instructionName(ins: Byte): String = when (ins.toInt() and 0xFF) {
        0xA4 -> "SELECT"
        0xB0 -> "READ BINARY"
        0xB1 -> "READ BINARY (odd)"
        0x84 -> "GET CHALLENGE"
        0x82 -> "EXTERNAL AUTHENTICATE"
        0x88 -> "INTERNAL AUTHENTICATE"
        0x22 -> "MSE"
        0x86, 0x87 -> "GENERAL AUTHENTICATE"
        else -> "INS %02X".format(ins)
    }
    
    private fun respondToCommandApdu(commandApdu: ByteArray?): ByteArray {
        val apduHex = commandApdu?.let { ApduParser.run { it.toHexString() } } ?: "null"
        Log.d(TAG, "Processing APDU: $apduHex")
        
        return try {
            // Validate APDU using SCUBA library
            val validationResult = validateApduCommand(commandApdu)
            if (!validationResult.isValid) {
                Log.w(TAG, "APDU validation failed: ${validationResult.errorMessage}")
                
                ErrorLogger.logError(
                    level = ErrorLogger.LogLevel.WARNING,
                    category = ErrorLogger.ErrorCategory.PROTOCOL_VIOLATION,
                    message = "APDU validation failed: ${validationResult.errorMessage}",
                    context = mapOf("apdu" to apduHex),
                    correlationId = sessionCorrelationId
                )
                
                emitEvent(NfcEvent.error(Clock.System.now(), "APDU validation failed: ${validationResult.errorMessage}"))
                return validationResult.errorResponse
            }
            
            // Mark connection as established on first valid APDU
            if (!isConnected) {
                isConnected = true
                ErrorLogger.logError(
                    level = ErrorLogger.LogLevel.INFO,
                    category = ErrorLogger.ErrorCategory.NFC_HARDWARE,
                    message = "NFC reader connected",
                    context = mapOf("apdu" to apduHex),
                    correlationId = sessionCorrelationId
                )
                emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "NFC reader connected"))
            }
            
            refreshPassportData()
            
            if (isSecureMessagingCommand(commandApdu!!)) {
                return processSecureMessagingCommand(commandApdu)
            }
            
            if (secureMessaging != null) {
                // ICAO 9303-11: a plain command aborts an established secure messaging session
                Log.w(TAG, "Plain APDU received during secure messaging; ending session")
                emitEvent(NfcEvent.error(Clock.System.now(), "Secure messaging session ended by plain command"))
                endSession()
            }
            
            processPlainCommand(commandApdu, isSecure = false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing APDU command", e)
            
            ErrorLogger.logError(
                level = ErrorLogger.LogLevel.ERROR,
                category = ErrorLogger.ErrorCategory.SYSTEM,
                message = "APDU processing error: ${e.message}",
                throwable = e,
                context = mapOf("apdu" to apduHex),
                correlationId = sessionCorrelationId
            )
            
            val error = SimulatorError.SystemError.UnexpectedError(e)
            val errorResponse = ErrorCodeMapper.mapError(error)
            emitEvent(NfcEvent.error(Clock.System.now(), errorResponse.message, errorResponse.errorCode))
            errorResponse.toByteArray()
        }
    }
    

    
    /**
     * Whether CLA indicates ISO 7816-4 secure messaging with an authenticated header.
     */
    private fun isSecureMessagingCommand(apdu: ByteArray): Boolean = (apdu[0].toInt() and 0x0C) == 0x0C
    
    /**
     * Verifies and decrypts a protected command, processes it, and protects the response.
     */
    private fun processSecureMessagingCommand(apdu: ByteArray): ByteArray {
        val secureMessaging = secureMessaging
        if (secureMessaging == null) {
            Log.w(TAG, "Secure messaging APDU received without an established session")
            emitEvent(NfcEvent.error(Clock.System.now(), "Secure messaging used before authentication"))
            return createErrorResponse(ErrorCodeMapper.SW_EXPECTED_SM_DATA_OBJECTS_MISSING)
        }
        
        val plainApdu = try {
            secureMessaging.unwrapCommand(apdu)
        } catch (e: ChipSecureMessaging.SecureMessagingException) {
            Log.w(TAG, "Secure messaging verification failed: ${e.message}")
            ErrorLogger.logError(
                level = ErrorLogger.LogLevel.WARNING,
                category = ErrorLogger.ErrorCategory.PROTOCOL_VIOLATION,
                message = "Secure messaging verification failed: ${e.message}",
                correlationId = sessionCorrelationId
            )
            emitEvent(NfcEvent.error(Clock.System.now(), "Secure messaging error: ${e.message}"))
            // The session is aborted; the reader has to authenticate again
            endSession()
            return createErrorResponse(ErrorCodeMapper.SW_SM_DATA_OBJECTS_INCORRECT)
        }
        
        val plainResponse = processPlainCommand(plainApdu, isSecure = true)
        plainExchange = plainApdu to plainResponse
        return secureMessaging.wrapResponse(plainResponse)
    }
    
    /**
     * Parses and executes an unprotected (or already unwrapped) command APDU.
     */
    private fun processPlainCommand(apdu: ByteArray, isSecure: Boolean): ByteArray {
        val apduHex = ApduParser.run { apdu.toHexString() }
        val parseResult = ApduParser.parseApduCommand(apdu)
        
        // Handle parsing errors
        if (!parseResult.isValid && parseResult.errorResponse != null) {
            Log.w(TAG, "APDU parsing failed: ${parseResult.commandType}")
            
            ErrorLogger.logError(
                level = ErrorLogger.LogLevel.WARNING,
                category = ErrorLogger.ErrorCategory.PROTOCOL_VIOLATION,
                message = "APDU parsing failed: ${parseResult.commandType}",
                context = mapOf("apdu" to apduHex, "commandType" to parseResult.commandType.toString()),
                correlationId = sessionCorrelationId
            )
            
            emitEvent(NfcEvent.error(Clock.System.now(), "Invalid APDU command: ${parseResult.commandType}"))
            return parseResult.errorResponse
        }
        
        return generateSmartCardResponse(parseResult, isSecure)
    }
    
    override fun onDeactivated(reason: Int) {
        val reasonString = when (reason) {
            DEACTIVATION_LINK_LOSS -> "Link loss"
            DEACTIVATION_DESELECTED -> "Deselected"
            else -> "Unknown reason ($reason)"
        }
        
        Log.d(TAG, "Service deactivated: $reasonString")
        
        ErrorLogger.logError(
            level = ErrorLogger.LogLevel.INFO,
            category = ErrorLogger.ErrorCategory.NFC_HARDWARE,
            message = "NFC service deactivated: $reasonString",
            context = mapOf("reason" to reason, "reasonString" to reasonString),
            correlationId = sessionCorrelationId
        )
        
        // Cancel any active timeout operations
        runBlocking {
            sessionCorrelationId?.let { timeoutHandler.cancelOperation(it) }
        }
        
        // Reset connection state; the reader has to select the application and authenticate again
        isConnected = false
        isApplicationSelected = false
        selectedFileId = null
        endSession()
        
        emitEvent(NfcEvent.connectionLost(Clock.System.now(), reasonString))
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PassportHceService destroyed")
        
        ErrorLogger.logError(
            level = ErrorLogger.LogLevel.INFO,
            category = ErrorLogger.ErrorCategory.SYSTEM,
            message = "PassportHceService destroyed",
            correlationId = sessionCorrelationId
        )
        
        // Cancel all active operations
        runBlocking {
            timeoutHandler.cancelAllOperations()
        }
        
        serviceScope.cancel()
    }
    
    /**
     * Picks up the simulation's current passport data. Data that changed (or a stopped simulation) ends the session,
     * so a restarted simulation takes effect from the next command.
     */
    private fun refreshPassportData() {
        val passportData = getSharedPassportData()
        if (passportData == currentPassportData) return
        
        endSession()
        isApplicationSelected = false
        selectedFileId = null
        currentPassportData = passportData
        ldsFiles = passportData?.let { loadPassportData(it) }
    }
    
    /**
     * Generates the LDS files for new passport data and derives the access keys.
     * Returns null if the data cannot be emulated.
     */
    private fun loadPassportData(passportData: PassportData): PassportLdsFiles? {
        val files = try {
            PassportLdsFiles.create(passportData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate passport files", e)
            emitEvent(NfcEvent.error(Clock.System.now(), "Passport file generation failed: ${e.message}"))
            return null
        }
        
        val bacInitResult = bacProtocol.initialize(passportData)
        val paceInitResult = paceProtocol.initialize(passportData, files.chipAuthenticationKeyPair)
        if (!bacInitResult.success || !paceInitResult.success) {
            val message = if (!bacInitResult.success) bacInitResult.message else paceInitResult.message
            Log.e(TAG, "Failed to initialize access control: $message")
            emitEvent(NfcEvent.error(Clock.System.now(), "Access control initialization failed: $message"))
            return null
        }
        
        val configuration = if (passportData.accessControl.supportsPace) {
            "${passportData.accessControl.displayName}, PACE-${passportData.paceMapping.abbreviation}" +
                if (passportData.hasCan()) ", CAN ${passportData.can}" else ""
        } else {
            passportData.accessControl.displayName
        }
        Log.d(TAG, "Chip ready with $configuration")
        emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "Chip ready ($configuration)"))
        return files
    }
    
    /**
     * Ends the secure messaging session and any access control run in progress.
     */
    private fun endSession() {
        secureMessaging = null
        bacProtocol.reset()
        paceProtocol.reset()
    }
    
    /**
     * Validates APDU command using SCUBA library.
     */
    private fun validateApduCommand(commandApdu: ByteArray?): ApduValidationResult {
        if (!isScubaInitialized) {
            return ApduValidationResult(
                false, 
                "SCUBA library not initialized", 
                createErrorResponse(ISO7816.SW_UNKNOWN.toInt())
            )
        }
        
        if (commandApdu == null || commandApdu.isEmpty()) {
            return ApduValidationResult(
                false, 
                "Empty APDU command", 
                createErrorResponse(ISO7816.SW_WRONG_LENGTH.toInt())
            )
        }
        
        try {
            // Use SCUBA CommandAPDU for validation
            val command = CommandAPDU(commandApdu)
            
            // Basic APDU structure validation
            if (command.cla.toInt() < 0 || command.ins.toInt() < 0) {
                return ApduValidationResult(
                    false, 
                    "Invalid APDU class or instruction", 
                    createErrorResponse(ISO7816.SW_CLA_NOT_SUPPORTED.toInt())
                )
            }
            
            // Log APDU details using SCUBA
            Log.d(TAG, "SCUBA APDU validation - CLA: ${String.format("0x%02X", command.cla.toInt())}, " +
                    "INS: ${String.format("0x%02X", command.ins.toInt())}, " +
                    "P1: ${String.format("0x%02X", command.p1.toInt())}, " +
                    "P2: ${String.format("0x%02X", command.p2.toInt())}, " +
                    "Data length: ${command.data?.size ?: 0}")
            
            return ApduValidationResult(true, "Valid APDU", byteArrayOf())
            
        } catch (e: Exception) {
            Log.e(TAG, "SCUBA APDU validation error", e)
            return ApduValidationResult(
                false, 
                "APDU validation exception: ${e.message}", 
                createErrorResponse(ISO7816.SW_WRONG_DATA.toInt())
            )
        }
    }
    
    /**
     * Generates smart card response using SCUBA library.
     * [isSecure] is true when the command arrived under BAC secure messaging.
     */
    private fun generateSmartCardResponse(parseResult: ApduParser.ApduParseResult, isSecure: Boolean): ByteArray {
        Log.d(TAG, "Generating smart card response using SCUBA")
        
        return when (parseResult.commandType) {
            ApduParser.ApduCommandType.SELECT -> handleSelectCommandWithScuba(parseResult, isSecure)
            ApduParser.ApduCommandType.READ_BINARY -> handleReadBinaryCommandWithScuba(parseResult, isSecure)
            ApduParser.ApduCommandType.GET_CHALLENGE -> handleGetChallengeCommandWithScuba(parseResult)
            ApduParser.ApduCommandType.EXTERNAL_AUTHENTICATE -> handleExternalAuthenticateCommandWithScuba(parseResult)
            ApduParser.ApduCommandType.MSE_SET_AT -> handleMseSetAtCommand(parseResult)
            ApduParser.ApduCommandType.GENERAL_AUTHENTICATE -> handleGeneralAuthenticateCommand(parseResult)
            ApduParser.ApduCommandType.INTERNAL_AUTHENTICATE -> {
                // Active Authentication needs a chip key pair in DG15, which this chip does not have
                Log.w(TAG, "Unsupported authentication command: ${parseResult.commandType}")
                emitEvent(NfcEvent.error(Clock.System.now(), "Active Authentication not supported"))
                createErrorResponse(ISO7816.SW_INS_NOT_SUPPORTED.toInt())
            }
            ApduParser.ApduCommandType.UNSUPPORTED -> {
                Log.w(TAG, "Unsupported APDU command")
                emitEvent(NfcEvent.error(Clock.System.now(), "Unsupported APDU command"))
                createErrorResponse(ISO7816.SW_INS_NOT_SUPPORTED.toInt())
            }
            ApduParser.ApduCommandType.INVALID -> {
                Log.w(TAG, "Invalid APDU command")
                emitEvent(NfcEvent.error(Clock.System.now(), "Invalid APDU command"))
                createErrorResponse(ISO7816.SW_WRONG_LENGTH.toInt())
            }
        }
    }
    
    /**
     * Creates a standardized error response using SCUBA constants.
     */
    private fun createErrorResponse(statusWord: Int): ByteArray {
        val sw1 = (statusWord shr 8).toByte()
        val sw2 = (statusWord and 0xFF).toByte()
        val response = ResponseAPDU(byteArrayOf(sw1, sw2))
        Log.d(TAG, "Created error response: ${String.format("0x%04X", statusWord)}")
        return response.bytes
    }
    
    /**
     * Creates a success response with optional data using SCUBA.
     */
    private fun createSuccessResponse(data: ByteArray = byteArrayOf()): ByteArray {
        val sw1 = (ISO7816.SW_NO_ERROR.toInt() shr 8).toByte()
        val sw2 = (ISO7816.SW_NO_ERROR.toInt() and 0xFF).toByte()
        val response = if (data.isNotEmpty()) {
            ResponseAPDU(data + byteArrayOf(sw1, sw2))
        } else {
            ResponseAPDU(byteArrayOf(sw1, sw2))
        }
        Log.d(TAG, "Created success response with ${data.size} bytes of data")
        return response.bytes
    }
    
    /**
     * Enhanced SELECT command handler using SCUBA library.
     * [isSecure] is true when the command arrived under secure messaging, as the application selection after PACE does.
     */
    private fun handleSelectCommandWithScuba(parseResult: ApduParser.ApduParseResult, isSecure: Boolean): ByteArray {
        Log.d(TAG, "Handling SELECT command with SCUBA")
        
        try {
            val files = ldsFiles
            
            // Case 1: Select by AID (P1=04); the parser only accepts the passport AID
            if (parseResult.p1 == 0x04) {
                if (files == null) {
                    Log.w(TAG, "Passport application selected while simulation is not running")
                    emitEvent(NfcEvent.error(Clock.System.now(), "Reader connected but simulation is not running"))
                    return createErrorResponse(ISO7816.SW_FILE_NOT_FOUND.toInt())
                }
                
                // A plain selection starts a new session; after PACE the reader selects the application under
                // secure messaging, which keeps the session
                if (!isSecure) {
                    endSession()
                }
                isApplicationSelected = true
                selectedFileId = null
                
                Log.d(TAG, "Passport application AID selected successfully")
                emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "Passport application selected"))
                
                // P2=0C asks for no response data; otherwise return the FCI with the DF name
                return if ((parseResult.p2 and 0x0C) == 0x0C) {
                    createSuccessResponse()
                } else {
                    val dfName = byteArrayOf(0x84.toByte(), ApduParser.PASSPORT_AID.size.toByte()) + ApduParser.PASSPORT_AID
                    createSuccessResponse(byteArrayOf(0x6F, dfName.size.toByte()) + dfName)
                }
            }
            
            // Case 2: Select by File ID (P1=02 or P1=00)
            val fileIdBytes = parseResult.data ?: return createErrorResponse(ISO7816.SW_WRONG_LENGTH.toInt())
            val fileId = (((fileIdBytes[0].toInt() and 0xFF) shl 8) or (fileIdBytes[1].toInt() and 0xFF)).toShort()
            val fileHex = ApduParser.run { fileIdBytes.toHexString() }
            Log.d(TAG, "Selecting file ID: $fileHex")
            
            if (fileId == MASTER_FILE_ID) {
                // Leaves the eMRTD application; a secure messaging session stays up
                isApplicationSelected = false
                selectedFileId = null
                return createSuccessResponse()
            }
            
            // EF.CardAccess and EF.CardSecurity live in the master file, the other EFs in the application
            if (files?.fileById(fileId, isApplicationSelected) == null) {
                Log.w(TAG, "Unknown File ID selected: $fileHex")
                selectedFileId = null
                return createErrorResponse(ISO7816.SW_FILE_NOT_FOUND.toInt())
            }
            
            selectedFileId = fileId
            emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "${files.fileName(fileId, isApplicationSelected)} selected"))
            return createSuccessResponse()

        } catch (e: Exception) {
            Log.e(TAG, "Error in SELECT command handling", e)
            emitEvent(NfcEvent.error(Clock.System.now(), "SELECT command error: ${e.message}"))
            return createErrorResponse(ISO7816.SW_UNKNOWN.toInt())
        }
    }
    
    /**
     * Enhanced READ BINARY command handler using SCUBA library.
     * Supports both the current-EF form (15-bit offset in P1-P2) and the short EF identifier form
     * (P1 = 0x80 | SFI, offset in P2), which also selects the file.
     */
    private fun handleReadBinaryCommandWithScuba(parseResult: ApduParser.ApduParseResult, isSecure: Boolean): ByteArray {
        Log.d(TAG, "Handling READ BINARY command with SCUBA")
        
        val files = ldsFiles ?: return createErrorResponse(ISO7816.SW_FILE_NOT_FOUND.toInt())
        
        val offset: Int
        if ((parseResult.p1 and 0x80) != 0) {
            val fileId = files.fidForSfi(parseResult.p1 and 0x1F, isApplicationSelected)
                ?: return createErrorResponse(ISO7816.SW_FILE_NOT_FOUND.toInt())
            selectedFileId = fileId
            offset = parseResult.p2
        } else {
            offset = (parseResult.p1 shl 8) or parseResult.p2
        }
        
        val fileId = selectedFileId
        if (fileId == null) {
            Log.w(TAG, "READ BINARY with no file selected")
            return createErrorResponse(ErrorCodeMapper.SW_COMMAND_NOT_ALLOWED)
        }
        val fileContent = files.fileById(fileId, isApplicationSelected) ?: return createErrorResponse(ISO7816.SW_FILE_NOT_FOUND.toInt())
        
        // EF.CardAccess is public; every other file can only be read under secure messaging
        if (!isSecure && !files.isPublic(fileId)) {
            Log.w(TAG, "READ BINARY attempted without authentication")
            emitEvent(NfcEvent.error(Clock.System.now(), "Authentication required for data access"))
            return createErrorResponse(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED.toInt())
        }
        
        if (offset >= fileContent.size) {
            return createErrorResponse(ISO7816.SW_WRONG_P1P2.toInt())
        }
        
        // Le=0 in a short APDU means up to 256 bytes
        val le = if (parseResult.le == 0) 256 else parseResult.le
        val bytesToRead = minOf(le, maxReadLength, fileContent.size - offset)
        val responseData = fileContent.copyOfRange(offset, offset + bytesToRead)
        
        val fileName = files.fileName(fileId, isApplicationSelected)
        Log.d(TAG, "READ BINARY success: File=$fileName, Offset=$offset, Length=$bytesToRead")
        // Only emit event for first chunk to avoid spamming
        if (offset == 0) {
            emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "Reading $fileName"))
        }
        
        return createSuccessResponse(responseData)
    }
    
    /**
     * Enhanced GET CHALLENGE command handler using BAC protocol.
     */
    private fun handleGetChallengeCommandWithScuba(parseResult: ApduParser.ApduParseResult): ByteArray {
        Log.d(TAG, "Handling GET CHALLENGE command with BAC protocol")
        
        try {
            // Check that the application was selected with passport data available
            if (!isApplicationSelected || ldsFiles == null) {
                Log.e(TAG, "No passport data available for BAC challenge")
                emitEvent(NfcEvent.error(Clock.System.now(), "No passport data configured"))
                return createErrorResponse(ISO7816.SW_CONDITIONS_NOT_SATISFIED.toInt())
            }
            
            if (currentPassportData?.accessControl?.supportsBac != true) {
                return bacDisabledResponse()
            }
            
            // Generate BAC challenge
            val challengeResult = bacProtocol.generateChallenge()
            
            if (challengeResult.success && challengeResult.data != null) {
                Log.d(TAG, "Generated BAC challenge: ${ApduParser.run { challengeResult.data.toHexString() }}")
                emitEvent(NfcEvent.bacAuthenticationRequest(Clock.System.now(), ApduParser.run { challengeResult.data.toHexString() }))
                return createSuccessResponse(challengeResult.data)
            } else {
                Log.e(TAG, "Failed to generate BAC challenge: ${challengeResult.message}")
                emitEvent(NfcEvent.error(Clock.System.now(), "BAC challenge generation failed: ${challengeResult.message}"))
                return createErrorResponse(ISO7816.SW_UNKNOWN.toInt())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating BAC challenge", e)
            emitEvent(NfcEvent.error(Clock.System.now(), "BAC challenge error: ${e.message}"))
            return createErrorResponse(ISO7816.SW_UNKNOWN.toInt())
        }
    }
    
    /**
     * Enhanced EXTERNAL AUTHENTICATE command handler using BAC protocol.
     */
    private fun handleExternalAuthenticateCommandWithScuba(parseResult: ApduParser.ApduParseResult): ByteArray {
        Log.d(TAG, "Handling EXTERNAL AUTHENTICATE command with BAC protocol")
        
        try {
            // Check if passport data is available
            val passportData = currentPassportData
            if (passportData == null || ldsFiles == null) {
                ErrorLogger.logAuthenticationError(
                    protocol = "BAC",
                    message = "No passport data available for BAC authentication",
                    error = SimulatorError.ProtocolError.BacAuthenticationFailed,
                    correlationId = sessionCorrelationId
                )
                emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "BAC", "No passport data configured"))
                val errorResponse = ErrorCodeMapper.mapError(SimulatorError.SystemError.ConfigurationError("No passport data configured"))
                return errorResponse.toByteArray()
            }
            
            if (!passportData.accessControl.supportsBac) {
                return bacDisabledResponse()
            }
            
            // Extract authentication data from APDU
            val authData = parseResult.data ?: byteArrayOf()
            if (authData.isEmpty()) {
                ErrorLogger.logAuthenticationError(
                    protocol = "BAC",
                    message = "No authentication data provided in EXTERNAL AUTHENTICATE",
                    error = SimulatorError.ProtocolError.InvalidApduCommand("No authentication data"),
                    correlationId = sessionCorrelationId
                )
                emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "BAC", "No authentication data"))
                val errorResponse = ErrorCodeMapper.mapError(SimulatorError.ProtocolError.InvalidApduCommand("No authentication data"))
                return errorResponse.toByteArray()
            }
            
            Log.d(TAG, "Processing BAC external authentication with ${authData.size} bytes of data")
            emitEvent(NfcEvent.bacAuthenticationRequest(Clock.System.now(), "Processing external authentication"))
            
            // Process BAC authentication
            val authResult = bacProtocol.processExternalAuthenticate(authData)
            
            if (authResult.success) {
                // Secure messaging is now established; later commands must be protected with the session keys
                secureMessaging = bacProtocol.getSecureMessaging()
                Log.d(TAG, "BAC authentication successful")
                
                ErrorLogger.logError(
                    level = ErrorLogger.LogLevel.INFO,
                    category = ErrorLogger.ErrorCategory.AUTHENTICATION,
                    message = "BAC authentication successful",
                    correlationId = sessionCorrelationId
                )
                
                emitEvent(NfcEvent.authenticationSuccess(Clock.System.now(), "BAC"))
                
                // Return authentication response data
                return if (authResult.data != null) {
                    ErrorCodeMapper.createSuccessResponse(authResult.data)
                } else {
                    ErrorCodeMapper.createSuccessResponse()
                }
            } else {
                ErrorLogger.logAuthenticationError(
                    protocol = "BAC",
                    message = "BAC authentication failed: ${authResult.message}",
                    error = SimulatorError.ProtocolError.BacAuthenticationFailed,
                    context = mapOf("bacMessage" to authResult.message),
                    correlationId = sessionCorrelationId
                )
                emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "BAC", authResult.message))
                val errorResponse = ErrorCodeMapper.mapError(SimulatorError.ProtocolError.BacAuthenticationFailed)
                return errorResponse.toByteArray()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in BAC EXTERNAL AUTHENTICATE", e)
            
            ErrorLogger.logAuthenticationError(
                protocol = "BAC",
                message = "Unexpected error in BAC EXTERNAL AUTHENTICATE: ${e.message}",
                error = SimulatorError.ProtocolError.BacAuthenticationFailed,
                correlationId = sessionCorrelationId
            )
            
            emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "BAC", e.message ?: "Unknown error"))
            val errorResponse = ErrorCodeMapper.mapError(SimulatorError.SystemError.UnexpectedError(e))
            return errorResponse.toByteArray()
        }
    }
    
    /**
     * BAC commands on a PACE-only chip.
     */
    private fun bacDisabledResponse(): ByteArray {
        Log.w(TAG, "BAC attempted on a PACE-only chip")
        emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "BAC", "BAC disabled; chip requires PACE"))
        return createErrorResponse(ISO7816.SW_INS_NOT_SUPPORTED.toInt())
    }
    
    /**
     * PACE commands on a BAC-only chip, which has no EF.CardAccess advertising PACE.
     */
    private fun paceDisabledResponse(): ByteArray {
        Log.w(TAG, "PACE attempted on a BAC-only chip")
        emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", "PACE disabled; chip requires BAC"))
        return createErrorResponse(ISO7816.SW_INS_NOT_SUPPORTED.toInt())
    }
    
    /**
     * MSE:Set AT selects the PACE protocol and password and starts a PACE run.
     */
    private fun handleMseSetAtCommand(parseResult: ApduParser.ApduParseResult): ByteArray {
        val passportData = currentPassportData
        if (passportData == null || ldsFiles == null) {
            emitEvent(NfcEvent.error(Clock.System.now(), "No passport data configured"))
            return createErrorResponse(ISO7816.SW_CONDITIONS_NOT_SATISFIED.toInt())
        }
        if (!passportData.accessControl.supportsPace) {
            return paceDisabledResponse()
        }
        
        val result = paceProtocol.processMseSetAt(parseResult.data ?: byteArrayOf())
        if (!result.success) {
            ErrorLogger.logAuthenticationError(
                protocol = "PACE",
                message = "MSE:Set AT rejected: ${result.message}",
                error = SimulatorError.ProtocolError.PaceAuthenticationFailed,
                correlationId = sessionCorrelationId
            )
            emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", result.message))
            return createErrorResponse(result.statusWord)
        }
        
        Log.d(TAG, result.message)
        emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), paceProtocol.oid))
        return createSuccessResponse()
    }
    
    /**
     * GENERAL AUTHENTICATE runs the four PACE steps; the last one establishes secure messaging.
     */
    private fun handleGeneralAuthenticateCommand(parseResult: ApduParser.ApduParseResult): ByteArray {
        if (currentPassportData?.accessControl?.supportsPace != true) {
            return paceDisabledResponse()
        }
        
        val result = paceProtocol.processGeneralAuthenticate(parseResult.data ?: byteArrayOf())
        if (!result.success) {
            ErrorLogger.logAuthenticationError(
                protocol = "PACE",
                message = "PACE authentication failed: ${result.message}",
                error = SimulatorError.ProtocolError.PaceAuthenticationFailed,
                context = mapOf("paceMessage" to result.message),
                correlationId = sessionCorrelationId
            )
            emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", result.message))
            return createErrorResponse(result.statusWord)
        }
        
        if (result.newState == PaceProtocol.PaceState.AUTHENTICATED) {
            // Secure messaging starts with the next command; this response is still sent in plain
            secureMessaging = paceProtocol.getSecureMessaging()
            ErrorLogger.logError(
                level = ErrorLogger.LogLevel.INFO,
                category = ErrorLogger.ErrorCategory.AUTHENTICATION,
                message = "PACE authentication successful",
                correlationId = sessionCorrelationId
            )
            emitEvent(NfcEvent.authenticationSuccess(Clock.System.now(), "PACE-${paceProtocol.getMapping().abbreviation}"))
        }
        
        return createSuccessResponse(result.data ?: byteArrayOf())
    }
    
    /**
     * Data class for APDU validation results.
     */
    private data class ApduValidationResult(
        val isValid: Boolean,
        val errorMessage: String,
        val errorResponse: ByteArray
    )
    
    /**
     * Emits an NFC event to the shared flow.
     */
    private fun emitEvent(event: NfcEvent) {
        _nfcEvents.tryEmit(event)
    }

}