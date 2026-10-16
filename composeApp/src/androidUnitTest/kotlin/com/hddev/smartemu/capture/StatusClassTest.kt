package com.hddev.smartemu.capture

import kotlin.test.Test
import kotlin.test.assertEquals

class StatusClassTest {
    @Test
    fun `after authentication began every failure stops the attempts, and only a success does not`() {
        for (statusWord in listOf(0x6300, 0x63C2, 0x6982, 0x6985, 0x6988, 0x6A80, 0x6700, 0x6983, 0x6F00, 0x6D00, null)) {
            assertEquals(true, StatusClass.of(statusWord).stopsFurtherAttempts, "%s".format(statusWord?.toString(16)))
        }
        assertEquals(false, StatusClass.OK.stopsFurtherAttempts)
    }

    @Test
    fun `before a key is sent only a plain not-supported lets the next suite be tried`() {
        for (statusWord in listOf(0x6A80, 0x6A81, 0x6A86, 0x6D00, 0x6E00, 0x6F00)) {
            assertEquals(true, StatusClass.of(statusWord).allowsNextSuiteBeforeKeyIsSent, "%04X".format(statusWord))
        }
        for (statusWord in listOf(0x6300, 0x63C1, 0x6982, 0x6985, 0x6988, 0x6700, 0x6C10, 0x6983, 0x6A82, null)) {
            assertEquals(false, StatusClass.of(statusWord).allowsNextSuiteBeforeKeyIsSent, "%s".format(statusWord?.toString(16)))
        }
    }
}
