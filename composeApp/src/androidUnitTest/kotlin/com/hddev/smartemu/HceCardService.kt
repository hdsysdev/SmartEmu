package com.hddev.smartemu

import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import org.jmrtd.PassportService
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Adapter that delivers reader APDUs to the HCE service, like Android's NFC stack does.
 */
class HceCardService(private val hceService: PassportHceService) : CardService() {
    private var isOpen = false
    override fun open() { isOpen = true }
    override fun isOpen(): Boolean = isOpen
    override fun transmit(commandAPDU: CommandAPDU): ResponseAPDU =
        ResponseAPDU(hceService.processCommandApdu(commandAPDU.bytes, null))
    override fun getATR(): ByteArray = byteArrayOf()
    override fun close() { isOpen = false }
    override fun isConnectionLost(e: Exception?): Boolean = false

    companion object {
        /**
         * Opens a JMRTD reader-side PassportService on the emulated chip.
         */
        fun openPassportService(hceService: PassportHceService, isSFIEnabled: Boolean = false): PassportService {
            val service = PassportService(
                HceCardService(hceService),
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                isSFIEnabled,
                true
            )
            service.open()
            return service
        }

        /**
         * The SmartEmu Test CSCA from the repository's test-pki directory (unit tests run in the module directory).
         */
        fun testCsca(): X509Certificate = File("../test-pki/csca/smartemu-test-csca.cert.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }
}
