package com.hddev.smartemu.recording

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.hddev.smartemu.SmartEmuApp
import com.hddev.smartemu.data.*
import com.hddev.smartemu.repository.*
import com.hddev.smartemu.viewmodel.PassportSimulatorViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Debug-only host: every visible screen is the normal production composable. */
class RecordingActivity : ComponentActivity() {
    private val fixture = RecordingNfcRepository()
    private val passportStore = object : PassportStore {
        private var passport: PassportData? = PassportData.empty().copy(can = "123456")
        override suspend fun load() = Result.success(passport)
        override suspend fun save(passportData: PassportData): Result<Unit> {
            passport = passportData
            return Result.success(Unit)
        }
    }
    private val model by lazy {
        PassportSimulatorViewModel(
            fixture, passportStore, ReadHistoryStore.InMemory(),
            SettingsStore.InMemory(AppSettings(introSeen = true, theme = AppTheme.LIGHT))
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { SmartEmuApp(viewModel = model) }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        when (intent.getStringExtra("command")) {
            "sample" -> model.autofillPassportData()
            "portrait" -> {
                val bytes = assets.open("recording-portrait.jpg").use { it.readBytes() }
                model.updatePortrait(Portrait(bytes, 480, 640))
            }
            "read" -> lifecycleScope.launch { fixture.read() }
        }
    }
}

/** Deterministic reader events exercise the real progress reducer and history. */
private class RecordingNfcRepository : NfcSimulatorRepository {
    private val status = MutableStateFlow(SimulationStatus.STOPPED)
    private val events = MutableSharedFlow<NfcEvent>(extraBufferCapacity = 32)
    override suspend fun startSimulation(passportData: PassportData): Result<Unit> {
        status.value = SimulationStatus.ACTIVE
        return Result.success(Unit)
    }
    override suspend fun stopSimulation(): Result<Unit> {
        status.value = SimulationStatus.STOPPED
        return Result.success(Unit)
    }
    override fun getSimulationStatus() = status
    override fun getNfcEvents() = events
    override suspend fun isNfcAvailable() = Result.success(true)
    override suspend fun hasNfcPermissions() = Result.success(true)
    override suspend fun requestNfcPermissions() = Result.success(true)
    override suspend fun clearEvents() = Unit

    suspend fun read() {
        events.emit(NfcEvent.connectionEstablished(Clock.System.now(), NfcEvent.READER_CONNECTED))
        delay(1800)
        events.emit(NfcEvent.authenticationSuccess(Clock.System.now(), "BAC"))
        for (file in listOf("EF.DG1", "EF.DG2", "EF.SOD")) {
            delay(2800)
            events.emit(NfcEvent.connectionEstablished(Clock.System.now(), NfcEvent.READING_PREFIX + file))
        }
        delay(2700)
        events.emit(NfcEvent.connectionLost(Clock.System.now(), "Fixture read complete"))
    }
}
