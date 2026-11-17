// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.hddev.passportemu.capture.probes

import com.hddev.passportemu.capture.Apdu
import com.hddev.passportemu.capture.ApduTransport
import com.hddev.passportemu.capture.LinkObservation
import com.hddev.passportemu.capture.ProbePack
import com.hddev.passportemu.capture.Probe
import com.hddev.passportemu.capture.ProbeSetup
import com.hddev.passportemu.capture.ProbeStep
import com.hddev.passportemu.capture.ReaderDevice
import com.hddev.passportemu.capture.Hex

/** Every command a step sends: its setup SELECTs, its preamble and its command, as the runner sends them. */
internal fun ProbeStep.allCommands(): List<ByteArray> {
    val masterFile = Apdu.selectFileId(0x3F00, p1 = 0x00)
    val setup = when (setup) {
        ProbeSetup.NONE -> emptyList()
        ProbeSetup.MASTER_FILE -> listOf(masterFile)
        ProbeSetup.EMRTD_APPLICATION -> listOf(masterFile, Apdu.selectEmrtdApplication)
        ProbeSetup.CARD_ACCESS_SELECTED -> listOf(masterFile, Apdu.selectFileId(0x011C))
    }
    return setup + preamble + listOf(command)
}

internal fun ProbePack.allSteps(): List<Pair<Probe, ProbeStep>> =
    probes.flatMap { probe -> probe.steps.map { probe to it } }

internal fun ByteArray.ins(): Int = this[1].toInt() and 0xFF

internal fun ByteArray.cla(): Int = this[0].toInt() and 0xFF

/** Passes commands on, keeps every one sent, and turns an exception of the chip into a recorded failure and a 6F00. */
internal class RecordingTransport(private val inner: ApduTransport) : ApduTransport {
    val sent = mutableListOf<ByteArray>()
    val chipFailures = mutableListOf<String>()

    override fun transmit(command: ByteArray): ByteArray {
        sent += command.copyOf()
        return try {
            inner.transmit(command)
        } catch (e: Throwable) {
            chipFailures += "${Hex.encode(command)} -> ${e::class.simpleName}: ${e.message}"
            byteArrayOf(0x6F, 0x00)
        }
    }

    override fun linkInfo(): LinkObservation? = inner.linkInfo()

    override fun readerDevice(): ReaderDevice = inner.readerDevice()
}
