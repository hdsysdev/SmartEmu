package com.hddev.smartemu.capture.auth

import java.util.logging.Level
import java.util.logging.Logger

/**
 * JMRTD and SCUBA log through `java.util.logging`, which Android sends to logcat, and what they log includes status
 * words, file lengths and the hex of responses ("Empty response data: ..."). A capture's rule is that no APDU data
 * reaches a log, so their loggers are switched off while an authenticated read runs and put back afterwards.
 */
internal object JmrtdLogging {
    private val names = listOf("org.jmrtd", "net.sf.scuba")

    fun <T> silenced(block: () -> T): T {
        // Held in a list so that the loggers aren't collected, which would drop the level, before it is put back
        val loggers = names.map { Logger.getLogger(it) }
        val previous = loggers.map { it.level }
        loggers.forEach { it.level = Level.OFF }
        try {
            return block()
        } finally {
            loggers.forEachIndexed { i, logger -> logger.level = previous[i] }
        }
    }
}
