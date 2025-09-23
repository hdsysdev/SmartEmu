package com.hddev.smartemu.data

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NfcEventTest {
    
    private val testTimestamp = Instant.fromEpochSeconds(1640995200) // 2022-01-01T00:00:00Z
    
    @Test
    fun testNfcEventCreation() {
        val event = NfcEvent(
            timestamp = testTimestamp,
            type = NfcEventType.CONNECTION_ESTABLISHED,
            message = "Test message",
            details = mapOf("key" to "value")
        )
        
        assertEquals(testTimestamp, event.timestamp)
        assertEquals(NfcEventType.CONNECTION_ESTABLISHED, event.type)
        assertEquals("Test message", event.message)
        assertEquals(mapOf("key" to "value"), event.details)
    }
    
    @Test
    fun testConnectionEstablishedFactory() {
        val event = NfcEvent.connectionEstablished(testTimestamp, "Reader123")
        
        assertEquals(testTimestamp, event.timestamp)
        assertEquals(NfcEventType.CONNECTION_ESTABLISHED, event.type)
        assertEquals("NFC connection established", event.message)
        assertEquals(mapOf("readerInfo" to "Reader123"), event.details)
    }
    
    @Test
    fun testConnectionEstablishedFactoryWithoutReaderInfo() {
        val event = NfcEvent.connectionEstablished(testTimestamp)
        
        assertEquals(testTimestamp, event.timestamp)
        assertEquals(NfcEventType.CONNECTION_ESTABLISHED, event.type)
        assertEquals("NFC connection established", event.message)
        assertEquals(emptyMap(), event.details)
    }
    
    @Test
    fun testBacAuthenticationRequestFactory() {
        val event = NfcEvent.bacAuthenticationRequest(testTimestamp, "challenge123")
        
        assertEquals(testTimestamp, event.timestamp)
        assertEquals(NfcEventType.BAC_AUTHENTICATION_REQUEST, event.type)
        assertEquals("BAC authentication requested", event.message)
        assertEquals(mapOf("challenge" to "challenge123"), event.details)
    }
    
    @Test
    fun testPaceAuthenticationRequestFactory() {
        val event = NfcEvent.paceAuthenticationRequest(testTimestamp, "PACE-ECDH")
        
        assertEquals(testTimestamp, event.timestamp)
        assertEquals(NfcEventType.PACE_AUTHENTICATION_REQUEST, event.type)
        assertEquals("PACE authentication requested", event.message)
        assertEquals(mapOf("protocol" to "PACE-ECDH"), event.details)
    }
    
    @Test
    fun testAuthenticationSuccessFactory() {
        val event = NfcEvent.authenticationSuccess(testTimestamp, "BAC")
        
        assertEquals(testTimestamp, event.timestamp)
        assertEquals(NfcEventType.AUTHENTICATION_SUCCESS, event.type)
        assertEquals("BAC authentication successful", event.message)
        assertEquals(mapOf("protocol" to "BAC"), event.details)
    }
    
    @Test
    fun testAuthenticationFailureFactory() {
        val event = NfcEvent.authenticationFailure(testTimestamp, "PACE", "Invalid key")
        
        assertEquals(testTimestamp, event.timestamp)
        assertEquals(NfcEventType.AUTHENTICATION_FAILURE, event.type)
        assertEquals("PACE authentication failed: Invalid key", event.message)
        assertEquals(mapOf("protocol" to "PACE", "reason" to "Invalid key"), event.details)
    }
    
    @Test
    fun testConnectionLostFactory() {
        val event = NfcEvent.connectionLost(testTimestamp, "Timeout")
        
        assertEquals(testTimestamp, event.timestamp)
        assertEquals(NfcEventType.CONNECTION_LOST, event.type)
        assertEquals("NFC connection lost", event.message)
        assertEquals(mapOf("reason" to "Timeout"), event.details)
    }
    
    @Test
    fun testErrorFactory() {
        val event = NfcEvent.error(testTimestamp, "Protocol error", "ERR_001")
        
        assertEquals(testTimestamp, event.timestamp)
        assertEquals(NfcEventType.ERROR, event.type)
        assertEquals("Error: Protocol error", event.message)
        assertEquals(mapOf("errorCode" to "ERR_001"), event.details)
    }
    
    @Test
    fun testErrorFactoryWithoutErrorCode() {
        val event = NfcEvent.error(testTimestamp, "General error")
        
        assertEquals(testTimestamp, event.timestamp)
        assertEquals(NfcEventType.ERROR, event.type)
        assertEquals("Error: General error", event.message)
        assertEquals(emptyMap(), event.details)
    }
}

class NfcEventTypeTest {
    
    @Test
    fun testGetDescription() {
        assertEquals("Connection Established", NfcEventType.CONNECTION_ESTABLISHED.getDescription())
        assertEquals("BAC Authentication Request", NfcEventType.BAC_AUTHENTICATION_REQUEST.getDescription())
        assertEquals("PACE Authentication Request", NfcEventType.PACE_AUTHENTICATION_REQUEST.getDescription())
        assertEquals("Authentication Success", NfcEventType.AUTHENTICATION_SUCCESS.getDescription())
        assertEquals("Authentication Failure", NfcEventType.AUTHENTICATION_FAILURE.getDescription())
        assertEquals("Connection Lost", NfcEventType.CONNECTION_LOST.getDescription())
        assertEquals("Error", NfcEventType.ERROR.getDescription())
    }
    
    @Test
    fun testIsError() {
        assertTrue(NfcEventType.AUTHENTICATION_FAILURE.isError())
        assertTrue(NfcEventType.ERROR.isError())
        
        assertFalse(NfcEventType.CONNECTION_ESTABLISHED.isError())
        assertFalse(NfcEventType.BAC_AUTHENTICATION_REQUEST.isError())
        assertFalse(NfcEventType.PACE_AUTHENTICATION_REQUEST.isError())
        assertFalse(NfcEventType.AUTHENTICATION_SUCCESS.isError())
        assertFalse(NfcEventType.CONNECTION_LOST.isError())
    }
    
    @Test
    fun testIsSuccess() {
        assertTrue(NfcEventType.CONNECTION_ESTABLISHED.isSuccess())
        assertTrue(NfcEventType.AUTHENTICATION_SUCCESS.isSuccess())
        
        assertFalse(NfcEventType.BAC_AUTHENTICATION_REQUEST.isSuccess())
        assertFalse(NfcEventType.PACE_AUTHENTICATION_REQUEST.isSuccess())
        assertFalse(NfcEventType.AUTHENTICATION_FAILURE.isSuccess())
        assertFalse(NfcEventType.CONNECTION_LOST.isSuccess())
        assertFalse(NfcEventType.ERROR.isSuccess())
    }
}