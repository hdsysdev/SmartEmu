package com.hddev.smartemu

import com.hddev.smartemu.data.NfcEvent
import com.hddev.smartemu.data.NfcEventType
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.utils.BacProtocol
import com.hddev.smartemu.utils.PaceProtocol
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Memory usage validation tests for the NFC simulation.
 * Ensures the application maintains reasonable memory usage during extended operations.
 */
class MemoryUsageValidationTest {
    
    private val validPassportData = PassportData(
        passportNumber = "L898902C3",
        dateOfBirth = LocalDate(1974, 8, 12),
        expiryDate = LocalDate(2025, 4, 15),
        issuingCountry = "NLD",
        nationality = "NLD",
        firstName = "ANNA",
        lastName = "ERIKSSON",
        gender = "F"
    )
    
    @Test
    fun `memory usage remains stable during extended BAC operations`() {
        val runtime = Runtime.getRuntime()
        
        // Force garbage collection and get baseline
        forceGarbageCollection()
        val initialMemory = getCurrentMemoryUsage(runtime)
        
        // Perform extended BAC operations
        repeat(1000) { iteration ->
            val bacProtocol = BacProtocol()
            bacProtocol.initialize(validPassportData)
            bacProtocol.generateChallenge()
            
            val mockAuthData = ByteArray(32) { it.toByte() }
            bacProtocol.processExternalAuthenticate(mockAuthData)
            
            // Check memory every 100 iterations
            if (iteration % 100 == 0 && iteration > 0) {
                forceGarbageCollection()
                val currentMemory = getCurrentMemoryUsage(runtime)
                val memoryIncrease = currentMemory - initialMemory
                
                assertTrue(
                    memoryIncrease < 20 * 1024 * 1024, // 20MB limit
                    "Memory usage should remain stable. Increase: ${memoryIncrease / 1024}KB at iteration $iteration"
                )
            }
        }
        
        // Final memory check
        forceGarbageCollection()
        val finalMemory = getCurrentMemoryUsage(runtime)
        val totalIncrease = finalMemory - initialMemory
        
        assertTrue(
            totalIncrease < 10 * 1024 * 1024, // 10MB final limit
            "Total memory increase should be minimal: ${totalIncrease / 1024}KB"
        )
    }
    
    @Test
    fun `memory usage remains stable during extended PACE operations`() {
        val runtime = Runtime.getRuntime()
        
        forceGarbageCollection()
        val initialMemory = getCurrentMemoryUsage(runtime)
        
        // Perform extended PACE operations
        repeat(500) { iteration ->
            val paceProtocol = PaceProtocol()
            paceProtocol.initialize(validPassportData)
            
            val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
            paceProtocol.processMseSetAt(mseData)
            paceProtocol.generateEncryptedNonce()
            
            val terminalPubKey = ByteArray(65) { it.toByte() }
            paceProtocol.processTerminalPublicKey(terminalPubKey)
            paceProtocol.performKeyAgreement()
            
            // Check memory every 50 iterations
            if (iteration % 50 == 0 && iteration > 0) {
                forceGarbageCollection()
                val currentMemory = getCurrentMemoryUsage(runtime)
                val memoryIncrease = currentMemory - initialMemory
                
                assertTrue(
                    memoryIncrease < 25 * 1024 * 1024, // 25MB limit (PACE is more complex)
                    "PACE memory usage should remain stable. Increase: ${memoryIncrease / 1024}KB at iteration $iteration"
                )
            }
        }
    }
    
    @Test
    fun `event log memory usage remains bounded`() {
        val runtime = Runtime.getRuntime()
        
        forceGarbageCollection()
        val initialMemory = getCurrentMemoryUsage(runtime)
        
        // Generate large number of events
        val events = mutableListOf<NfcEvent>()
        repeat(10000) { i ->
            events.add(NfcEvent(
                timestamp = Clock.System.now(),
                type = NfcEventType.values()[i % NfcEventType.values().size],
                message = "Event message $i with some additional details to make it longer",
                details = mapOf(
                    "iteration" to i,
                    "timestamp" to Clock.System.now().toString(),
                    "data" to ByteArray(100) { (it + i).toByte() }.contentToString()
                )
            ))
            
            // Check memory every 1000 events
            if (i % 1000 == 0 && i > 0) {
                forceGarbageCollection()
                val currentMemory = getCurrentMemoryUsage(runtime)
                val memoryIncrease = currentMemory - initialMemory
                
                assertTrue(
                    memoryIncrease < 50 * 1024 * 1024, // 50MB limit for large event log
                    "Event log memory should remain bounded. Increase: ${memoryIncrease / 1024}KB with $i events"
                )
            }
        }
        
        // Test event log cleanup
        events.clear()
        forceGarbageCollection()
        
        val afterCleanupMemory = getCurrentMemoryUsage(runtime)
        val memoryAfterCleanup = afterCleanupMemory - initialMemory
        
        assertTrue(
            memoryAfterCleanup < 10 * 1024 * 1024, // Should return close to baseline
            "Memory should be reclaimed after event cleanup: ${memoryAfterCleanup / 1024}KB"
        )
    }
    
    @Test
    fun `passport data validation memory usage`() {
        val runtime = Runtime.getRuntime()
        
        forceGarbageCollection()
        val initialMemory = getCurrentMemoryUsage(runtime)
        
        // Test with various passport data sizes
        repeat(1000) { iteration ->
            // Create passport data with varying field lengths
            val passportData = PassportData(
                passportNumber = "A".repeat((iteration % 20) + 1),
                dateOfBirth = LocalDate(1900 + (iteration % 100), 1, 1),
                expiryDate = LocalDate(2025 + (iteration % 10), 1, 1),
                issuingCountry = "USA",
                nationality = "USA",
                firstName = "F".repeat((iteration % 30) + 1),
                lastName = "L".repeat((iteration % 30) + 1),
                gender = if (iteration % 2 == 0) "M" else "F"
            )
            
            // Validate and generate MRZ
            val isValid = passportData.isValid()
            val mrzData = passportData.toMrzData()
            
            // Use the results to prevent optimization
            assertTrue(mrzData.isNotEmpty() || !isValid)
            
            if (iteration % 200 == 0 && iteration > 0) {
                forceGarbageCollection()
                val currentMemory = getCurrentMemoryUsage(runtime)
                val memoryIncrease = currentMemory - initialMemory
                
                assertTrue(
                    memoryIncrease < 15 * 1024 * 1024, // 15MB limit
                    "Passport validation memory should remain stable: ${memoryIncrease / 1024}KB at iteration $iteration"
                )
            }
        }
    }
    
    @Test
    fun `concurrent protocol operations memory usage`() = runBlocking {
        val runtime = Runtime.getRuntime()
        
        forceGarbageCollection()
        val initialMemory = getCurrentMemoryUsage(runtime)
        
        // Create multiple protocol instances concurrently
        val bacProtocols = List(10) { BacProtocol() }
        val paceProtocols = List(10) { PaceProtocol() }
        
        // Initialize all protocols
        bacProtocols.forEach { it.initialize(validPassportData) }
        paceProtocols.forEach { it.initialize(validPassportData) }
        
        forceGarbageCollection()
        val afterInitMemory = getCurrentMemoryUsage(runtime)
        val initMemoryIncrease = afterInitMemory - initialMemory
        
        assertTrue(
            initMemoryIncrease < 30 * 1024 * 1024, // 30MB for 20 protocol instances
            "Concurrent protocol initialization memory: ${initMemoryIncrease / 1024}KB"
        )
        
        // Perform operations on all protocols
        repeat(100) { iteration ->
            bacProtocols.forEach { protocol ->
                protocol.generateChallenge()
                val mockAuthData = ByteArray(32) { it.toByte() }
                protocol.processExternalAuthenticate(mockAuthData)
                protocol.reset()
                protocol.initialize(validPassportData)
            }
            
            paceProtocols.forEach { protocol ->
                val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
                protocol.processMseSetAt(mseData)
                protocol.generateEncryptedNonce()
                protocol.reset()
                protocol.initialize(validPassportData)
            }
            
            if (iteration % 25 == 0 && iteration > 0) {
                forceGarbageCollection()
                val currentMemory = getCurrentMemoryUsage(runtime)
                val memoryIncrease = currentMemory - initialMemory
                
                assertTrue(
                    memoryIncrease < 40 * 1024 * 1024, // 40MB limit for concurrent operations
                    "Concurrent operations memory: ${memoryIncrease / 1024}KB at iteration $iteration"
                )
            }
        }
    }
    
    @Test
    fun `memory leak detection in protocol reset`() {
        val runtime = Runtime.getRuntime()
        
        forceGarbageCollection()
        val initialMemory = getCurrentMemoryUsage(runtime)
        
        // Create and destroy many protocol instances
        repeat(1000) { iteration ->
            val bacProtocol = BacProtocol()
            bacProtocol.initialize(validPassportData)
            bacProtocol.generateChallenge()
            
            val mockAuthData = ByteArray(32) { it.toByte() }
            bacProtocol.processExternalAuthenticate(mockAuthData)
            
            // Reset should clean up all internal state
            bacProtocol.reset()
            
            val paceProtocol = PaceProtocol()
            paceProtocol.initialize(validPassportData)
            
            val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
            paceProtocol.processMseSetAt(mseData)
            paceProtocol.generateEncryptedNonce()
            
            // Reset should clean up all internal state
            paceProtocol.reset()
            
            // Check for memory leaks every 100 iterations
            if (iteration % 100 == 0 && iteration > 0) {
                forceGarbageCollection()
                val currentMemory = getCurrentMemoryUsage(runtime)
                val memoryIncrease = currentMemory - initialMemory
                
                assertTrue(
                    memoryIncrease < 5 * 1024 * 1024, // Very strict limit for leak detection
                    "Potential memory leak detected. Increase: ${memoryIncrease / 1024}KB at iteration $iteration"
                )
            }
        }
        
        // Final leak check
        forceGarbageCollection()
        val finalMemory = getCurrentMemoryUsage(runtime)
        val totalIncrease = finalMemory - initialMemory
        
        assertTrue(
            totalIncrease < 2 * 1024 * 1024, // 2MB final limit
            "Memory leak detected. Total increase: ${totalIncrease / 1024}KB"
        )
    }
    
    private fun forceGarbageCollection() {
        // Force garbage collection multiple times to ensure cleanup
        repeat(3) {
            System.gc()
            System.runFinalization()
            Thread.sleep(100)
        }
    }
    
    private fun getCurrentMemoryUsage(runtime: Runtime): Long {
        return runtime.totalMemory() - runtime.freeMemory()
    }
}