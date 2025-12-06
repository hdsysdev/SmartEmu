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
import com.hddev.smartemu.utils.PaceProtocol
import com.hddev.smartemu.utils.ErrorLogger
import com.hddev.smartemu.utils.ErrorCodeMapper
import com.hddev.smartemu.utils.TimeoutHandler
import com.hddev.smartemu.utils.ErrorRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import net.sf.scuba.smartcards.ISO7816
import java.security.SecureRandom
import java.util.UUID

/**
 * Host Card Emulation service for simulating passport NFC chip.
 * Handles APDU commands and emulates passport authentication protocols.
 */
class PassportHceService : HostApduService() {
    
    companion object {
        private const val TAG = "PassportHceService"
        
        // Shared event flow for communication with the app
        private val _nfcEvents = MutableSharedFlow<NfcEvent>(replay = 0, extraBufferCapacity = 100)
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
    private var isAuthenticated = false
    private var currentProtocol: String? = null
    
    // Error handling and recovery
    private val timeoutHandler = TimeoutHandler()
    private val errorRecovery = ErrorRecovery()
    private var sessionCorrelationId: String? = null
    
    // SCUBA library components
    private val secureRandom = SecureRandom()
    private var isScubaInitialized = false
    
    // BAC protocol handler
    private val bacProtocol = BacProtocol()
    
    // PACE protocol handler
    private val paceProtocol = PaceProtocol()
    
    private var currentPassportData: PassportData? = null
    
    // Passport application AID (as per ICAO 9303)
    private val passportAid = byteArrayOf(
        0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01
    )
    
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
        
        // Initialize with shared passport data if available
        val sharedData = getSharedPassportData()
        if (sharedData != null) {
            setPassportData(sharedData)
        }
        
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
                
                // Initialize secure random for cryptographic operations
                secureRandom.setSeed(System.currentTimeMillis())
                
                // Simulate potential initialization failure for testing
                if (attemptNumber == 1 && Math.random() < 0.1) {
                    throw RuntimeException("Simulated SCUBA initialization failure")
                }
                
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
            
            // Parse the APDU command
            val parseResult = ApduParser.parseApduCommand(commandApdu)
            
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
            
            // Generate smart card response using SCUBA
            generateSmartCardResponse(parseResult)
            
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
        
        // Reset connection state
        isConnected = false
        isAuthenticated = false
        currentProtocol = null
        
        // Reset BAC and PACE protocol states
        bacProtocol.reset()
        paceProtocol.reset()
        
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
     * Sets the passport data for BAC and PACE authentication.
     * This method should be called before starting NFC simulation.
     */
    fun setPassportData(passportData: PassportData) {
        currentPassportData = passportData
        
        // Initialize BAC protocol
        val bacInitResult = bacProtocol.initialize(passportData)
        if (bacInitResult.success) {
            Log.d(TAG, "BAC protocol initialized successfully")
            emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "BAC protocol ready"))
        } else {
            Log.e(TAG, "Failed to initialize BAC protocol: ${bacInitResult.message}")
            emitEvent(NfcEvent.error(Clock.System.now(), "BAC initialization failed: ${bacInitResult.message}"))
        }
        
        // Initialize PACE protocol
        val paceInitResult = paceProtocol.initialize(passportData)
        if (paceInitResult.success) {
            Log.d(TAG, "PACE protocol initialized successfully")
            emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "PACE protocol ready"))
        } else {
            Log.e(TAG, "Failed to initialize PACE protocol: ${paceInitResult.message}")
            emitEvent(NfcEvent.error(Clock.System.now(), "PACE initialization failed: ${paceInitResult.message}"))
        }
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
     */
    private fun generateSmartCardResponse(parseResult: ApduParser.ApduParseResult): ByteArray {
        Log.d(TAG, "Generating smart card response using SCUBA")
        
        return when (parseResult.commandType) {
            ApduParser.ApduCommandType.SELECT -> handleSelectCommandWithScuba(parseResult)
            ApduParser.ApduCommandType.READ_BINARY -> handleReadBinaryCommandWithScuba(parseResult)
            ApduParser.ApduCommandType.GET_CHALLENGE -> handleGetChallengeCommandWithScuba(parseResult)
            ApduParser.ApduCommandType.EXTERNAL_AUTHENTICATE -> handleExternalAuthenticateCommandWithScuba(parseResult)
            ApduParser.ApduCommandType.INTERNAL_AUTHENTICATE -> handleInternalAuthenticateCommandWithScuba(parseResult)
            ApduParser.ApduCommandType.MSE_SET_AT -> handleMseSetAtCommandWithPace(parseResult)
            ApduParser.ApduCommandType.GENERAL_AUTHENTICATE -> handleGeneralAuthenticateCommandWithPace(parseResult)
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
     */
    private fun handleSelectCommandWithScuba(parseResult: ApduParser.ApduParseResult): ByteArray {
        Log.d(TAG, "Handling SELECT command with SCUBA")
        
        try {
            val aidData = parseResult.data
            if (aidData != null && aidData.contentEquals(passportAid)) {
                Log.d(TAG, "Passport application AID selected successfully")
                emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "Passport application selected"))
                
                // Return File Control Information (FCI) for passport application
                val fciData = byteArrayOf(
                    0x6F.toByte(), 0x10.toByte(), // FCI template
                    0x84.toByte(), 0x07.toByte(), // DF name
                ) + passportAid + byteArrayOf(
                    0xA5.toByte(), 0x05.toByte(), // Proprietary information
                    0x9F.toByte(), 0x6E.toByte(), 0x02.toByte(), 0x00.toByte(), 0x00.toByte() // Application production life cycle data
                )
                
                return createSuccessResponse(fciData)
            } else {
                val aidHex = aidData?.let { ApduParser.run { it.toHexString() } } ?: "unknown"
                Log.w(TAG, "Unknown AID selected: $aidHex")
                emitEvent(NfcEvent.error(Clock.System.now(), "Unknown AID selected: $aidHex"))
                return createErrorResponse(ISO7816.SW_FILE_NOT_FOUND.toInt())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SELECT command handling", e)
            emitEvent(NfcEvent.error(Clock.System.now(), "SELECT command error: ${e.message}"))
            return createErrorResponse(ISO7816.SW_UNKNOWN.toInt())
        }
    }
    
    /**
     * Enhanced READ BINARY command handler using SCUBA library.
     */
    private fun handleReadBinaryCommandWithScuba(parseResult: ApduParser.ApduParseResult): ByteArray {
        Log.d(TAG, "Handling READ BINARY command with SCUBA")
        
        if (!isAuthenticated) {
            Log.w(TAG, "READ BINARY attempted without authentication")
            emitEvent(NfcEvent.error(Clock.System.now(), "Authentication required for data access"))
            return createErrorResponse(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED.toInt())
        }
        
        // Placeholder response - will be enhanced in later tasks with actual passport data
        Log.d(TAG, "READ BINARY successful (placeholder)")
        emitEvent(NfcEvent.connectionEstablished(Clock.System.now(), "Data file accessed"))
        return createSuccessResponse()
    }
    
    /**
     * Enhanced GET CHALLENGE command handler using BAC protocol.
     */
    private fun handleGetChallengeCommandWithScuba(parseResult: ApduParser.ApduParseResult): ByteArray {
        Log.d(TAG, "Handling GET CHALLENGE command with BAC protocol")
        
        try {
            // Check if passport data is available
            if (currentPassportData == null) {
                Log.e(TAG, "No passport data available for BAC challenge")
                emitEvent(NfcEvent.error(Clock.System.now(), "No passport data configured"))
                return createErrorResponse(ISO7816.SW_CONDITIONS_NOT_SATISFIED.toInt())
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
            currentProtocol = "BAC"
            
            // Check if passport data is available
            if (currentPassportData == null) {
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
                isAuthenticated = true
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
     * Enhanced INTERNAL AUTHENTICATE command handler using SCUBA library.
     */
    private fun handleInternalAuthenticateCommandWithScuba(parseResult: ApduParser.ApduParseResult): ByteArray {
        Log.d(TAG, "Handling INTERNAL AUTHENTICATE command with SCUBA")
        
        try {
            currentProtocol = "PACE"
            emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), "Internal authentication"))
            
            // Placeholder implementation - will be enhanced in PACE task
            // For now, simulate successful authentication
            isAuthenticated = true
            emitEvent(NfcEvent.authenticationSuccess(Clock.System.now(), "PACE"))
            
            return createSuccessResponse()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in INTERNAL AUTHENTICATE", e)
            emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", e.message ?: "Unknown error"))
            return createErrorResponse(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED.toInt())
        }
    }
    
    /**
     * Handles MSE SET AT command for PACE protocol initialization.
     */
    private fun handleMseSetAtCommandWithPace(parseResult: ApduParser.ApduParseResult): ByteArray {
        Log.d(TAG, "Handling MSE SET AT command for PACE protocol")
        
        try {
            currentProtocol = "PACE"
            
            // Check if passport data is available
            if (currentPassportData == null) {
                Log.e(TAG, "No passport data available for PACE MSE SET AT")
                emitEvent(NfcEvent.error(Clock.System.now(), "No passport data configured"))
                return createErrorResponse(ISO7816.SW_CONDITIONS_NOT_SATISFIED.toInt())
            }
            
            // Extract MSE SET AT data from APDU
            val mseData = parseResult.data ?: byteArrayOf()
            
            Log.d(TAG, "Processing PACE MSE SET AT with ${mseData.size} bytes of data")
            emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), "MSE SET AT for PACE"))
            
            // Process MSE SET AT with PACE protocol
            val mseResult = paceProtocol.processMseSetAt(mseData)
            
            if (mseResult.success) {
                Log.d(TAG, "PACE MSE SET AT processed successfully")
                emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), "PACE parameters established"))
                return createSuccessResponse()
            } else {
                Log.e(TAG, "PACE MSE SET AT failed: ${mseResult.message}")
                emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", mseResult.message))
                return createErrorResponse(ISO7816.SW_WRONG_DATA.toInt())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in PACE MSE SET AT", e)
            emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", e.message ?: "Unknown error"))
            return createErrorResponse(ISO7816.SW_UNKNOWN.toInt())
        }
    }
    
    /**
     * Handles GENERAL AUTHENTICATE command for PACE protocol steps.
     */
    private fun handleGeneralAuthenticateCommandWithPace(parseResult: ApduParser.ApduParseResult): ByteArray {
        Log.d(TAG, "Handling GENERAL AUTHENTICATE command for PACE protocol")
        
        try {
            currentProtocol = "PACE"
            
            // Check if passport data is available
            if (currentPassportData == null) {
                Log.e(TAG, "No passport data available for PACE GENERAL AUTHENTICATE")
                emitEvent(NfcEvent.error(Clock.System.now(), "No passport data configured"))
                return createErrorResponse(ISO7816.SW_CONDITIONS_NOT_SATISFIED.toInt())
            }
            
            // Extract authentication data from APDU
            val authData = parseResult.data ?: byteArrayOf()
            val currentStep = paceProtocol.getCurrentStep()
            
            Log.d(TAG, "Processing PACE GENERAL AUTHENTICATE step $currentStep with ${authData.size} bytes of data")
            
            return when (paceProtocol.getCurrentState()) {
                PaceProtocol.PaceState.MSE_SET_AT_PROCESSED -> {
                    // Step 1: Generate encrypted nonce
                    emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), "PACE step 1: Generate nonce"))
                    val nonceResult = paceProtocol.generateEncryptedNonce()
                    
                    if (nonceResult.success && nonceResult.data != null) {
                        Log.d(TAG, "PACE encrypted nonce generated")
                        emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), "Encrypted nonce generated"))
                        createSuccessResponse(nonceResult.data)
                    } else {
                        Log.e(TAG, "PACE nonce generation failed: ${nonceResult.message}")
                        emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", nonceResult.message))
                        createErrorResponse(ISO7816.SW_UNKNOWN.toInt())
                    }
                }
                
                PaceProtocol.PaceState.NONCE_GENERATED -> {
                    // Step 2: Process terminal public key
                    emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), "PACE step 2: Process terminal key"))
                    val keyResult = paceProtocol.processTerminalPublicKey(authData)
                    
                    if (keyResult.success && keyResult.data != null) {
                        Log.d(TAG, "PACE terminal public key processed")
                        emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), "Terminal key processed"))
                        createSuccessResponse(keyResult.data)
                    } else {
                        Log.e(TAG, "PACE terminal key processing failed: ${keyResult.message}")
                        emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", keyResult.message))
                        createErrorResponse(ISO7816.SW_WRONG_DATA.toInt())
                    }
                }
                
                PaceProtocol.PaceState.KEY_AGREEMENT_IN_PROGRESS -> {
                    // Step 3: Perform key agreement
                    emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), "PACE step 3: Key agreement"))
                    val agreementResult = paceProtocol.performKeyAgreement()
                    
                    if (agreementResult.success && agreementResult.data != null) {
                        Log.d(TAG, "PACE key agreement completed")
                        emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), "Key agreement completed"))
                        createSuccessResponse(agreementResult.data)
                    } else {
                        Log.e(TAG, "PACE key agreement failed: ${agreementResult.message}")
                        emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", agreementResult.message))
                        createErrorResponse(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED.toInt())
                    }
                }
                
                PaceProtocol.PaceState.MUTUAL_AUTHENTICATION -> {
                    // Step 4: Verify terminal authentication
                    emitEvent(NfcEvent.paceAuthenticationRequest(Clock.System.now(), "PACE step 4: Verify authentication"))
                    val authResult = paceProtocol.verifyTerminalAuthentication(authData)
                    
                    if (authResult.success) {
                        isAuthenticated = true
                        Log.d(TAG, "PACE authentication completed successfully")
                        emitEvent(NfcEvent.authenticationSuccess(Clock.System.now(), "PACE"))
                        createSuccessResponse()
                    } else {
                        Log.e(TAG, "PACE authentication verification failed: ${authResult.message}")
                        emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", authResult.message))
                        createErrorResponse(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED.toInt())
                    }
                }
                
                else -> {
                    Log.w(TAG, "PACE GENERAL AUTHENTICATE in invalid state: ${paceProtocol.getCurrentState()}")
                    emitEvent(NfcEvent.error(Clock.System.now(), "Invalid PACE state for GENERAL AUTHENTICATE"))
                    createErrorResponse(ISO7816.SW_CONDITIONS_NOT_SATISFIED.toInt())
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in PACE GENERAL AUTHENTICATE", e)
            emitEvent(NfcEvent.authenticationFailure(Clock.System.now(), "PACE", e.message ?: "Unknown error"))
            return createErrorResponse(ISO7816.SW_UNKNOWN.toInt())
        }
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
        serviceScope.launch {
            _nfcEvents.emit(event)
        }
    }

}