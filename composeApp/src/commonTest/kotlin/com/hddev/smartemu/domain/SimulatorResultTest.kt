package com.hddev.smartemu.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulatorResultTest {
    
    @Test
    fun `SimulatorResultExtensions success creates successful result`() {
        val result = SimulatorResultExtensions.success("test value")
        
        assertTrue(result.isSuccess)
        assertEquals("test value", result.getOrNull())
    }
    
    @Test
    fun `SimulatorResultExtensions failure with SimulatorError creates failed result`() {
        val error = SimulatorError.ValidationError.InvalidPassportNumber
        val result = SimulatorResultExtensions.failure<String>(error)
        
        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }
    
    @Test
    fun `SimulatorResultExtensions failure with Throwable creates failed result with UnexpectedError`() {
        val throwable = RuntimeException("test error")
        val result = SimulatorResultExtensions.failure<String>(throwable)
        
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is SimulatorError.SystemError.UnexpectedError)
        assertEquals(throwable, (exception as SimulatorError.SystemError.UnexpectedError).throwable)
    }
    
    @Test
    fun `getSimulatorError returns SimulatorError from failed result`() {
        val error = SimulatorError.ValidationError.InvalidDateOfBirth
        val result = Result.failure<String>(error)
        
        assertEquals(error, result.getSimulatorError())
    }
    
    @Test
    fun `getSimulatorError returns null for successful result`() {
        val result = Result.success("test")
        
        assertNull(result.getSimulatorError())
    }
    
    @Test
    fun `getSimulatorError returns null for non-SimulatorError exception`() {
        val result = Result.failure<String>(RuntimeException("test"))
        
        assertNull(result.getSimulatorError())
    }
    
    @Test
    fun `getUserMessage returns user-friendly message from SimulatorError`() {
        val error = SimulatorError.ValidationError.InvalidPassportNumber
        val result = Result.failure<String>(error)
        
        assertEquals(error.toUserMessage(), result.getUserMessage())
    }
    
    @Test
    fun `getUserMessage returns null for successful result`() {
        val result = Result.success("test")
        
        assertNull(result.getUserMessage())
    }
    
    @Test
    fun `isRecoverable returns true for recoverable errors`() {
        val error = SimulatorError.ValidationError.InvalidPassportNumber
        val result = Result.failure<String>(error)
        
        assertTrue(result.isRecoverable())
    }
    
    @Test
    fun `isRecoverable returns false for non-recoverable errors`() {
        val error = SimulatorError.NfcError.NfcNotAvailable
        val result = Result.failure<String>(error)
        
        assertFalse(result.isRecoverable())
    }
    
    @Test
    fun `isRecoverable returns false for successful result`() {
        val result = Result.success("test")
        
        assertFalse(result.isRecoverable())
    }
    
    @Test
    fun `toSimulatorResult preserves successful result`() {
        val originalResult = Result.success("test value")
        val simulatorResult = originalResult.toSimulatorResult()
        
        assertTrue(simulatorResult.isSuccess)
        assertEquals("test value", simulatorResult.getOrNull())
    }
    
    @Test
    fun `toSimulatorResult preserves SimulatorError in failed result`() {
        val error = SimulatorError.ValidationError.InvalidExpiryDate
        val originalResult = Result.failure<String>(error)
        val simulatorResult = originalResult.toSimulatorResult()
        
        assertTrue(simulatorResult.isFailure)
        assertEquals(error, simulatorResult.exceptionOrNull())
    }
    
    @Test
    fun `toSimulatorResult converts regular exception to UnexpectedError`() {
        val throwable = RuntimeException("test error")
        val originalResult = Result.failure<String>(throwable)
        val simulatorResult = originalResult.toSimulatorResult()
        
        assertTrue(simulatorResult.isFailure)
        val exception = simulatorResult.exceptionOrNull()
        assertTrue(exception is SimulatorError.SystemError.UnexpectedError)
        assertEquals(throwable, (exception as SimulatorError.SystemError.UnexpectedError).throwable)
    }
    
    @Test
    fun `safeCall returns success for successful block`() {
        val result = safeCall { "test value" }
        
        assertTrue(result.isSuccess)
        assertEquals("test value", result.getOrNull())
    }
    
    @Test
    fun `safeCall catches SimulatorError and returns failure`() {
        val error = SimulatorError.ValidationError.InvalidCountryCode
        val result = safeCall<String> { throw error }
        
        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }
    
    @Test
    fun `safeCall catches regular exception and wraps in UnexpectedError`() {
        val throwable = RuntimeException("test error")
        val result = safeCall<String> { throw throwable }
        
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is SimulatorError.SystemError.UnexpectedError)
        assertEquals(throwable, (exception as SimulatorError.SystemError.UnexpectedError).throwable)
    }
    
    @Test
    fun `safeSuspendCall returns success for successful suspend block`() = kotlinx.coroutines.test.runTest {
        val result = safeSuspendCall { 
            kotlinx.coroutines.delay(1)
            "test value" 
        }
        
        assertTrue(result.isSuccess)
        assertEquals("test value", result.getOrNull())
    }
    
    @Test
    fun `safeSuspendCall catches SimulatorError and returns failure`() = kotlinx.coroutines.test.runTest {
        val error = SimulatorError.NfcError.PermissionDenied
        val result = safeSuspendCall<String> { 
            kotlinx.coroutines.delay(1)
            throw error 
        }
        
        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }
    
    @Test
    fun `safeSuspendCall catches regular exception and wraps in UnexpectedError`() = kotlinx.coroutines.test.runTest {
        val throwable = RuntimeException("async error")
        val result = safeSuspendCall<String> { 
            kotlinx.coroutines.delay(1)
            throw throwable 
        }
        
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is SimulatorError.SystemError.UnexpectedError)
        assertEquals(throwable, (exception as SimulatorError.SystemError.UnexpectedError).throwable)
    }
}