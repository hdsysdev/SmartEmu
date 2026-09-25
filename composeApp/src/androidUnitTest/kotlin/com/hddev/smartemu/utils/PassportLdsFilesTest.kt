package com.hddev.smartemu.utils

import com.hddev.smartemu.HceCardService
import com.hddev.smartemu.data.AccessControl
import com.hddev.smartemu.data.PaceMapping
import com.hddev.smartemu.data.PassportData
import com.hddev.smartemu.data.Portrait
import kotlinx.datetime.LocalDate
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.CardSecurityFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checks the generated LDS files and that [PassportData.toMrzData] agrees with JMRTD's MRZ encoding.
 */
class PassportLdsFilesTest {
    
    private val passportData = PassportData(
        passportNumber = "L898902C3",
        dateOfBirth = LocalDate(1974, 8, 12),
        expiryDate = LocalDate(2034, 4, 15),
        issuingCountry = "NLD",
        nationality = "NLD",
        firstName = "ANNA MARIA",
        lastName = "ERIKSSON",
        gender = "F"
    )
    
    private fun dg1Mrz(data: PassportData): String {
        val dg1 = PassportLdsFiles.create(data).applicationFile(PassportService.EF_DG1)!!
        return DG1File(ByteArrayInputStream(dg1)).mrzInfo.toString().replace("\n", "")
    }
    
    @Test
    fun `MRZ matches JMRTD encoding including check digits`() {
        assertEquals(passportData.toMrzData(), dg1Mrz(passportData))
        assertEquals(88, passportData.toMrzData().length)
    }
    
    @Test
    fun `unspecified sex is encoded as a filler`() {
        val data = passportData.copy(gender = "X")
        
        assertEquals('<', data.toMrzData()[44 + 20])
        assertEquals(data.toMrzData(), dg1Mrz(data))
    }
    
    @Test
    fun `names longer than the MRZ name field are truncated consistently`() {
        val data = passportData.copy(firstName = "A".repeat(39), lastName = "B".repeat(39))
        
        assertEquals(data.toMrzData(), dg1Mrz(data))
    }
    
    @Test
    fun `stray separators in names do not create a name field separator`() {
        val data = passportData.copy(firstName = "Anne--Marie", lastName = "Smith-")
        
        assertEquals(data.toMrzData(), dg1Mrz(data))
        assertEquals("P<NLDSMITH<<ANNE<MARIE", data.toMrzData().substring(0, 44).trimEnd('<'))
    }
    
    @Test
    fun `short file identifiers resolve to the LDS files`() {
        val files = PassportLdsFiles.create(passportData)
        
        assertEquals(PassportService.EF_DG1, files.fidForSfi(PassportService.SFI_DG1.toInt(), isApplicationSelected = true))
        assertEquals(PassportService.EF_SOD, files.fidForSfi(PassportService.SFI_SOD.toInt(), isApplicationSelected = true))
        assertNotNull(files.applicationFile(PassportService.EF_COM))
    }
    
    @Test
    fun `EF CardAccess is present only when the chip supports PACE`() {
        for (accessControl in listOf(AccessControl.BAC_AND_PACE, AccessControl.PACE_ONLY)) {
            val files = PassportLdsFiles.create(passportData.copy(accessControl = accessControl))
            val cardAccess = CardAccessFile(ByteArrayInputStream(files.masterFile(PassportService.EF_CARD_ACCESS)!!))
            
            assertEquals(PaceProtocol.oid(PaceMapping.GENERIC), cardAccess.securityInfos.filterIsInstance<PACEInfo>().single().objectIdentifier)
            assertEquals(PassportService.EF_CARD_ACCESS, files.fidForSfi(PassportService.SFI_CARD_ACCESS.toInt(), isApplicationSelected = false))
            assertTrue(files.isPublic(PassportService.EF_CARD_ACCESS))
        }
        
        val bacOnly = PassportLdsFiles.create(passportData.copy(accessControl = AccessControl.BAC_ONLY))
        assertNull(bacOnly.masterFile(PassportService.EF_CARD_ACCESS))
        assertNull(bacOnly.fidForSfi(PassportService.SFI_CARD_ACCESS.toInt(), isApplicationSelected = false))
    }
    
    @Test
    fun `EF CardAccess advertises the chosen PACE mapping`() {
        for (mapping in PaceMapping.entries) {
            val files = PassportLdsFiles.create(passportData.copy(paceMapping = mapping))
            val cardAccess = CardAccessFile(ByteArrayInputStream(files.masterFile(PassportService.EF_CARD_ACCESS)!!))

            assertEquals(PaceProtocol.oid(mapping), cardAccess.securityInfos.filterIsInstance<PACEInfo>().single().objectIdentifier)
        }
    }

    @Test
    fun `EF CardSecurity with the chip key exists only for PACE-CAM`() {
        val cam = PassportLdsFiles.create(passportData.copy(paceMapping = PaceMapping.CHIP_AUTHENTICATION))
        val cardSecurity = CardSecurityFile(ByteArrayInputStream(cam.masterFile(PassportService.EF_CARD_SECURITY)!!))

        val chipKey = cardSecurity.chipAuthenticationPublicKeyInfos.single().subjectPublicKey
        assertEquals(cam.chipAuthenticationKeyPair!!.public, chipKey)
        assertEquals(PaceProtocol.oid(PaceMapping.CHIP_AUTHENTICATION), cardSecurity.paceInfos.single().objectIdentifier)
        assertFalse(cam.isPublic(PassportService.EF_CARD_SECURITY))
        assertEquals(PassportService.EF_CARD_SECURITY, cam.fidForSfi(PassportService.SFI_CARD_SECURITY.toInt(), isApplicationSelected = false))

        val generic = PassportLdsFiles.create(passportData.copy(paceMapping = PaceMapping.GENERIC))
        assertNull(generic.masterFile(PassportService.EF_CARD_SECURITY))
        assertNull(generic.chipAuthenticationKeyPair)
        val bacOnly = PassportLdsFiles.create(
            passportData.copy(accessControl = AccessControl.BAC_ONLY, paceMapping = PaceMapping.CHIP_AUTHENTICATION)
        )
        assertNull(bacOnly.masterFile(PassportService.EF_CARD_SECURITY))
    }

    @Test
    fun `file identifier 011D is EF CardSecurity in the MF and EF SOD in the application`() {
        val cam = PassportLdsFiles.create(passportData.copy(paceMapping = PaceMapping.CHIP_AUTHENTICATION))

        val inMasterFile = cam.fileById(PassportService.EF_CARD_SECURITY, isApplicationSelected = false)!!
        val inApplication = cam.fileById(PassportService.EF_SOD, isApplicationSelected = true)!!

        CardSecurityFile(ByteArrayInputStream(inMasterFile))
        SODFile(ByteArrayInputStream(inApplication))
        assertEquals("EF.CardSecurity", cam.fileName(PassportService.EF_CARD_SECURITY, isApplicationSelected = false))
        assertEquals("EF.SOD", cam.fileName(PassportService.EF_SOD, isApplicationSelected = true))
        // EF.CardAccess has its own identifier and stays reachable from the application
        assertNotNull(cam.fileById(PassportService.EF_CARD_ACCESS, isApplicationSelected = true))
        assertNull(cam.fileById(PassportService.EF_DG1, isApplicationSelected = false))
    }

    @Test
    fun `EF SOD is signed by the repository's test Document Signer issued by the test CSCA`() {
        val sod = SODFile(ByteArrayInputStream(PassportLdsFiles.create(passportData).applicationFile(PassportService.EF_SOD)!!))
        val csca = HceCardService.testCsca()
        
        assertEquals(PassportLdsFiles.documentSignerCertificate, sod.docSigningCertificate)
        assertEquals("CN=SmartEmu Test Document Signer,OU=Test PKI,O=SmartEmu,C=UT", sod.docSigningCertificate.subjectX500Principal.name)
        sod.docSigningCertificate.verify(csca.publicKey)
        sod.docSigningCertificate.checkValidity()
    }

    @Test
    fun `EF DG2 serves the holder's portrait when there is one`() {
        // DG2 carries the JPEG as is, so any bytes stand in for one
        val jpeg = ByteArray(1_000) { it.toByte() }
        val portrait = Portrait(jpeg = jpeg, width = Portrait.WIDTH, height = Portrait.HEIGHT)

        val withPortrait = faceImage(passportData.copy(portrait = portrait))
        val placeholder = faceImage(passportData)

        assertTrue(jpeg.contentEquals(withPortrait.imageInputStream.readBytes()))
        assertEquals(Portrait.WIDTH, withPortrait.width)
        assertEquals(Portrait.HEIGHT, withPortrait.height)
        assertEquals(FaceImageInfo.IMAGE_COLOR_SPACE_RGB24, withPortrait.colorSpace)
        assertEquals(8, placeholder.width)
        assertEquals(FaceImageInfo.IMAGE_COLOR_SPACE_GRAY8, placeholder.colorSpace)
    }

    @Test
    fun `passport data with equal portraits is equal, so the chip isn't rebuilt`() {
        val first = passportData.copy(portrait = Portrait(byteArrayOf(1, 2, 3), 3, 4))
        val second = passportData.copy(portrait = Portrait(byteArrayOf(1, 2, 3), 3, 4))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(first == passportData.copy(portrait = Portrait(byteArrayOf(1, 2, 4), 3, 4)))
    }

    private fun faceImage(data: PassportData): FaceImageInfo {
        val dg2 = DG2File(ByteArrayInputStream(PassportLdsFiles.create(data).applicationFile(PassportService.EF_DG2)!!))
        return dg2.faceInfos.single().faceImageInfos.single()
    }
}
