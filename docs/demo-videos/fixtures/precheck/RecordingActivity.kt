package com.t4connex.precheck.recording

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import com.t4connex.precheck.setupKoinApplication
import com.t4connex.precheck.common.camera.*
import com.t4connex.precheck.common.components.ButtonParams
import com.t4connex.precheck.checks.entities.PrecheckEntity
import com.t4connex.precheck.checks.services.PrecheckService
import com.t4connex.precheck.documents.entities.DocumentEntity
import com.t4connex.precheck.documents.repositories.DocumentCrudRepository
import com.t4connex.precheck.documents.services.DocumentService
import com.t4connex.precheck.steps.main.work.RightToWorkDocumentType
import com.t4connex.precheck.steps.main.work.fields.DocumentDetailsView
import com.t4connex.precheck.steps.main.work.biometrics.capture.DocumentBiometricsView
import com.t4connex.precheck.steps.main.work.biometrics.capture.DocumentBiometricsViewModel
import com.t4connex.precheck.steps.main.work.biometrics.capture.model.NFCState
import com.t4connex.precheck.theme.PrecheckTheme
import com.t4connex.documentform.library.library.DocumentFormLibrary
import com.t4connex.r2w.camera.library.CameraLibrary
import com.t4connex.r2w.camera.model.getImage
import com.t4connex.r2w.common.library.CommonLibrary
import com.t4connex.r2w.common.library.useLibraries
import com.t4connex.r2w.common.components.dialog.Dialog
import com.t4connex.r2w.common.components.dialog.useDialog
import com.t4connex.r2w.common.components.dialog.openAppStoreObj
import com.t4connex.r2w.common.navigation.animations.resetAppObj
import com.t4connex.r2w.common.database.encryption.toEncrypted
import com.t4connex.r2w.nfc.library.NFCLibrary
import com.t4connex.r2w.nfc.model.NFCProgressItem
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext
import precheck.app.generated.resources.*

/** Debug-only recording host. All visible pages come from the production app. */
class RecordingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupKoinApplication()
        val koin = GlobalContext.get()
        koin.declare<DocumentService>(FixtureDocumentService(), allowOverride = true)
        koin.declare<PrecheckService>(FixtureCheckService(), allowOverride = true)
        val page = intent.getStringExtra("page") ?: "capture"
        setContent {
            resetAppObj.implement { recreate() }
            openAppStoreObj.implement { /* Recording host has no store navigation. */ }
            useLibraries(CommonLibrary, CameraLibrary, NFCLibrary, DocumentFormLibrary)
            PrecheckTheme(darkTheme = false) {
                Surface { RecordingFlow(page) }
            }
        }
    }

    @Composable
    private fun RecordingFlow(initial: String) {
        var page by remember { mutableStateOf(initial) }
        val image = remember { assets.open("recording-specimen.png").use { it.readBytes() }.getImage() }
        when (page) {
            "capture", "captured" -> {
                val photo = remember {
                    PrecheckPhotoDisplayScope { page = "captured" }.apply {
                        if (initial == "captured") setImage(image)
                    }
                }
                LaunchedEffect(page) { if (page == "captured") photo.setImage(image) }
                with(photo) {
                    // The exact component used by PassportCaptureView and its
                    // DocumentPhotoCaptureView wrapper, supplied a fixed image.
                    PrecheckPagePhotoDisplayView(
                        title = stringResource(Res.string.passport_capture_title),
                        instruction = stringResource(Res.string.passport_capture_instructions),
                        progress = .5f,
                        sampleImage = SampleImage(Res.drawable.passport_sample),
                        next = ButtonParams(stringResource(Res.string.next)) { page = "details" },
                        back = ButtonParams(stringResource(Res.string.back)) { page = "capture" },
                    )
                }
            }
            "details" -> DocumentDetailsView(
                documentId = "recording-document",
                onNext = { page = "read" },
                onBack = { page = "captured" },
            )
            "read" -> {
                val model = remember { DocumentBiometricsViewModel() }
                val state by model.uiState.collectAsState()
                val dialog = useDialog()
                val success = stringResource(Res.string.biometrics_capture_success, "passport")
                DocumentBiometricsView(
                    checkId = "recording-check",
                    documentId = "recording-document",
                    viewModel = model,
                    onBack = { page = "details" },
                    onFinish = { page = "details" },
                )
                // Start Reading is the real button and NFC lifecycle. Controlled
                // reader callbacks drive the original view model's progress UI.
                LaunchedEffect(state.nfcState) {
                    if (state.nfcState == NFCState.SEARCHING) {
                        delay(1600)
                        model.onConnectTag()
                        model.onReading()
                    }
                    if (state.nfcState == NFCState.READING) {
                        for (part in 0..2) {
                            for (percent in 0..20) {
                                model.onProgress(NFCProgressItem(
                                    currentDGProgress = percent / 20f,
                                    currentDGIndex = part,
                                    totalDGs = 3,
                                ))
                                delay(180)
                            }
                        }
                        dialog.titleMessageAndActions(
                            Res.string.biometrics_capture_finished,
                            success,
                            Dialog.ButtonParams(positiveAction = { page = "details" }),
                        )
                    }
                }
            }
        }
    }
}

/** In-memory fixture: existing services' consumers and forms remain unchanged. */
private class FixtureDocumentService : DocumentService(
    GlobalContext.get().get(), GlobalContext.get().get(), GlobalContext.get().get(),
    GlobalContext.get().get(), GlobalContext.get().get(),
) {
    private val specimen = DocumentEntity(
        id = "recording-document",
        documentType = "PASSPORT",
        documentCategory = "IDVT",
        documentNumber = "123456789".toEncrypted(),
        nationality = "GBR".toEncrypted(),
        countryOfIssue = "GBR".toEncrypted(),
        dateOfBirth = LocalDate(1980, 1, 1),
        expiryDate = LocalDate(2030, 1, 1),
        issueDate = LocalDate(2020, 1, 1),
    )
    override suspend fun fetchDocument(documentId: String) = specimen
    override suspend fun updateDocument(
        documentId: String,
        request: DocumentCrudRepository.DocumentRecordUpdateRequest,
    ): DocumentEntity {
        request.documentNumber?.let { specimen.documentNumber = it.toEncrypted() }
        request.nationality?.let { specimen.nationality = it.toEncrypted() }
        request.expiryDate?.let { specimen.expiryDate = it }
        request.issueDate?.let { specimen.issueDate = it }
        return specimen
    }
}

private class FixtureCheckService : PrecheckService(
    GlobalContext.get().get(), GlobalContext.get().get(), GlobalContext.get().get(),
    GlobalContext.get().get(), GlobalContext.get().get(), GlobalContext.get().get(),
) {
    override suspend fun fetchCheck(checkId: String) = PrecheckEntity(
        id = "recording-check", dob = LocalDate(1980, 1, 1),
        forename = "John".toEncrypted(), surname = "Smith".toEncrypted(),
    )
}
