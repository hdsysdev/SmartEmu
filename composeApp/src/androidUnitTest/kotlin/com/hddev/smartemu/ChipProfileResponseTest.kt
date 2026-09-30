package com.hddev.smartemu

import com.hddev.smartemu.data.ChipProfiles
import com.hddev.smartemu.data.PassportPresets
import kotlinx.datetime.LocalDate
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/** Distinguishes the study's malformed probes from a valid-length BAC request sent out of sequence. */
class ChipProfileResponseTest {
    private lateinit var chip: PassportHceService

    @Before fun setUp() {
        chip = PassportHceService().also { it.onCreate() }
    }

    @After fun tearDown() {
        PassportHceService.setSharedPassportData(null)
        chip.onDestroy()
    }

    @Test fun `Spanish measured status applies only to the 40 byte request`() {
        val profile = ChipProfiles.byId("es-passport-2008-study")
        val document = PassportPresets.byId("profile-${profile.id}")!!.build(LocalDate(2026, 9, 30))
        PassportHceService.setSharedPassportData(document)
        val reader = HceCardService.openPassportService(chip)
        reader.sendSelectApplet(false)

        for (length in listOf(0, 1, 39, 41, 64)) {
            val command = if (length == 0) CommandAPDU(0, 0x82, 0, 0) else
                CommandAPDU(0, 0x82, 0, 0, ByteArray(length), 40)
            val response = ResponseAPDU(chip.processCommandApdu(command.bytes, null))
            assertEquals(0x6700, response.sw, "Invalid length $length must not get the measured 6300 status")
        }
        val response = ResponseAPDU(chip.processCommandApdu(CommandAPDU(0, 0x82, 0, 0, ByteArray(40), 40).bytes, null))
        assertEquals(0x6300, response.sw)
    }
}
