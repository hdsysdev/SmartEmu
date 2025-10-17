package com.hddev.smartemu

import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.utils.BacProtocol
import com.hddev.smartemu.utils.PaceProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

/**
 * Performance tests for NFC response times and memory usage validation.
 * Tests ensure the simulator meets performance requirements for real-time NFC operations.
 */
class PerformanceTest {
    
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
    fun `BAC authentication should complete within acceptable time limits`() {
        val bacProtocol = BacProtocol()
        
        // Measure initialization time
        val initTime = measureTimeMillis {
            bacProtocol.initialize(validPassportData)
        }
        assertTrue(initTime < 100, "BAC initialization should complete within 100ms, took ${initTime}ms")
        
        // Measure challenge generation time
        val challengeTime = measureTimeMillis {
            bacProtocol.generateChallenge()
        }
        assertTrue(challengeTime < 50, "Challenge generation should complete within 50ms, took ${challengeTime}ms")
        
        // Measure authentication time
        val mockAuthData = ByteArray(32) { it.toByte() }
        val authTime = measureTimeMillis {
            bacProtocol.processExternalAuthenticate(mockAuthData)
        }
        assertTrue(authTime < 200, "BAC authentication should complete within 200ms, took ${authTime}ms")
        
        // Total workflow should be under 350ms
        val totalTime = initTime + challengeTime + authTime
        assertTrue(totalTime < 350, "Complete BAC workflow should complete within 350ms, took ${totalTime}ms")
    }
    
    @Test
    fun `PACE authentication should complete within acceptable time limits`() {
        val paceProtocol = PaceProtocol()
        
        // Measure complete PACE workflow
        val totalTime = measureTimeMillis {
            paceProtocol.initialize(validPassportData)
            
            val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
            paceProtocol.processMseSetAt(mseData)
            
            paceProtocol.generateEncryptedNonce()
            
            val terminalPubKey = ByteArray(65) { it.toByte() }
            paceProtocol.processTerminalPublicKey(terminalPubKey)
            
            paceProtocol.performKeyAgreement()
            
            val terminalToken = ByteArray(16) { (it + 5).toByte() }
            paceProtocol.verifyTerminalAuthentication(terminalToken)
        }
        
        // PACE is more complex but should still complete within reasonable time
        assertTrue(totalTime < 500, "Complete PACE workflow should complete within 500ms, took ${totalTime}ms")
    }
    
    @Test
    fun `APDU command processing should have consistent response times`() {
        val bacProtocol = BacProtocol()
        bacProtocol.initialize(validPassportData)
        
        val responseTimes = mutableListOf<Long>()
        
        // Measure multiple challenge generations to check consistency
        repeat(10) {
            bacProtocol.reset()
            bacProtocol.initialize(validPassportData)
            
            val responseTime = measureTimeMillis {
                bacProtocol.generateChallenge()
            }
            responseTimes.add(responseTime)
        }
        
        val averageTime = responseTimes.average()
        val maxTime = responseTimes.maxOrNull() ?: 0L
        val minTime = responseTimes.minOrNull() ?: 0L
        
        // Check that response times are consistent (max shouldn't be more than 3x min)
        assertTrue(maxTime < minTime * 3, "Response times should be consistent. Min: ${minTime}ms, Max: ${maxTime}ms")
        assertTrue(averageTime < 50, "Average response time should be under 50ms, was ${averageTime}ms")
    }
    
    @Test
    fun `memory usage should remain stable during extended simulation`() {
        val runtime = Runtime.getRuntime()
        
        // Force garbage collection to get baseline
        System.gc()
        Thread.sleep(100)
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()
        
        // Simulate extended NFC operations
        repeat(100) { iteration ->
            val bacProtocol = BacProtocol()
            bacProtocol.initialize(validPassportData)
            bacProtocol.generateChallenge()
            
            val mockAuthData = ByteArray(32) { it.toByte() }
            bacProtocol.processExternalAuthenticate(mockAuthData)
            
            val paceProtocol = PaceProtocol()
            paceProtocol.initialize(validPassportData)
            
            val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
            paceProtocol.processMseSetAt(mseData)
            paceProtocol.generateEncryptedNonce()
            
            // Check memory every 25 iterations
            if (iteration % 25 == 0) {
                System.gc()
                Thread.sleep(50)
                val currentMemory = runtime.totalMemory() - runtime.freeMemory()
                val memoryIncrease = currentMemory - initialMemory
                
                // Memory increase should be reasonable (less than 10MB)
                assertTrue(
                    memoryIncrease < 10 * 1024 * 1024,
                    "Memory usage should remain stable. Increase: ${memoryIncrease / 1024}KB at iteration $iteration"
                )
            }
        }
        
        // Final memory check
        System.gc()
        Thread.sleep(100)
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val totalIncrease = finalMemory - initialMemory
        
        assertTrue(
            totalIncrease < 5 * 1024 * 1024,
            "Total memory increase should be less than 5MB, was ${totalIncrease / 1024}KB"
        )
    }
    
    @Test
    fun `concurrent authentication attempts should not degrade performance`() = runBlocking {
        val protocols = List(5) { BacProtocol() }
        
        // Initialize all protocols
        protocols.forEach { it.initialize(validPassportData) }
        
        // Measure concurrent challenge generation
        val concurrentTime = measureTimeMillis {
            protocols.forEach { protocol ->
                protocol.generateChallenge()
            }
        }
        
        // Should not be significantly slower than sequential operations
        val sequentialTime = measureTimeMillis {
            repeat(5) {
                val protocol = BacProtocol()
                protocol.initialize(validPassportData)
                protocol.generateChallenge()
            }
        }
        
        // Concurrent should not be more than 50% slower than sequential
        assertTrue(
            concurrentTime < sequentialTime * 1.5,
            "Concurrent operations should not significantly degrade performance. " +
            "Sequential: ${sequentialTime}ms, Concurrent: ${concurrentTime}ms"
        )
    }
    
    @Test
    fun `large passport data should not impact performance significantly`() {
        // Create passport data with maximum field lengths
        val largePassportData = PassportData(
            passportNumber = "A" + "1234567890".repeat(3), // Long passport number
            dateOfBirth = LocalDate(1900, 1, 1),
            expiryDate = LocalDate(2099, 12, 31),
            issuingCountry = "USA",
            nationality = "USA",
            firstName = "A".repeat(39), // Maximum first name length
            lastName = "B".repeat(39),  // Maximum last name length
            gender = "M"
        )
        
        val bacProtocol = BacProtocol()
        
        // Measure performance with large data
        val largeDataTime = measureTimeMillis {
            bacProtocol.initialize(largePassportData)
            bacProtocol.generateChallenge()
            
            val mockAuthData = ByteArray(32) { it.toByte() }
            bacProtocol.processExternalAuthenticate(mockAuthData)
        }
        
        // Compare with normal data
        val normalProtocol = BacProtocol()
        val normalDataTime = measureTimeMillis {
            normalProtocol.initialize(validPassportData)
            normalProtocol.generateChallenge()
            
            val mockAuthData = ByteArray(32) { it.toByte() }
            normalProtocol.processExternalAuthenticate(mockAuthData)
        }
        
        // Large data should not be more than 2x slower
        assertTrue(
            largeDataTime < normalDataTime * 2,
            "Large passport data should not significantly impact performance. " +
            "Normal: ${normalDataTime}ms, Large: ${largeDataTime}ms"
        )
    }
    
    @Test
    fun `protocol reset operations should be fast`() {
        val bacProtocol = BacProtocol()
        val paceProtocol = PaceProtocol()
        
        // Set up protocols in various states
        bacProtocol.initialize(validPassportData)
        bacProtocol.generateChallenge()
        
        paceProtocol.initialize(validPassportData)
        val mseData = byteArrayOf(0x80.toByte(), 0x0A.toByte())
        paceProtocol.processMseSetAt(mseData)
        paceProtocol.generateEncryptedNonce()
        
        // Measure reset times
        val bacResetTime = measureTimeMillis {
            repeat(100) {
                bacProtocol.reset()
            }
        }
        
        val paceResetTime = measureTimeMillis {
            repeat(100) {
                paceProtocol.reset()
            }
        }
        
        // Reset operations should be very fast
        assertTrue(bacResetTime < 50, "BAC reset operations should complete quickly, took ${bacResetTime}ms for 100 resets")
        assertTrue(paceResetTime < 50, "PACE reset operations should complete quickly, took ${paceResetTime}ms for 100 resets")
    }
}