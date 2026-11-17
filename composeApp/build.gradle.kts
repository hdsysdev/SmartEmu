// SPDX-FileCopyrightText: 2025-2026 PassportEmu contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    sourceSets {
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.scuba.smartcards)
            implementation(libs.jmrtd)
            implementation(libs.bouncycastle.pkix)
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.compose.materialIconsExtended)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.mockk)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Keep generated resource imports stable when the app's display name changes.
compose.resources {
    packageOfResClass = "passportemu.composeapp.generated.resources"
}

// The release signing key's location and passwords, from local.properties (git-ignored; the key lives in keystore/,
// also git-ignored). The older smartemu.signing.* names are still read. Without them, assembleRelease builds an
// unsigned APK, so a fresh clone can build a release.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use(::load)
}
fun signingProperty(name: String): String? =
    localProperties.getProperty("passportemu.signing.$name") ?: localProperties.getProperty("smartemu.signing.$name")
val releaseSigningConfigured = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { signingProperty(it) != null }

// Capture mode reads a real passport or ID card. It is compiled in only for developer builds made with
// -Ppassportemu.captureMode=true, never for a published APK; see docs/capture-guide.md
val captureMode = providers.gradleProperty("passportemu.captureMode").orNull == "true"

android {
    namespace = "com.hddev.passportemu"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hddev.passportemu"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("boolean", "CAPTURE_MODE", captureMode.toString())
        manifestPlaceholders["captureModeEnabled"] = captureMode.toString()
    }
    buildFeatures {
        buildConfig = true
    }
    // Test Document Signers that sign EF.SOD; see test-pki/README.md. The app reads only <name>.cert.pem and
    // <name>.key.pem, so the DER copies are kept out of the APK below.
    sourceSets["main"].resources.srcDir(rootProject.file("test-pki/document-signer"))
    packaging {
        resources {
            excludes += "/*.cert.der"
            // Never ship a PKCS#12 key store that ends up in the folder
            excludes += "/*.p12"
            // The Apache-2.0 and LGPL-2.1 notices several dependencies carry; identical copies, so one of each ships
            pickFirsts += "/META-INF/{AL2.0,LGPL2.1}"
            // Duplicated by the BouncyCastle jars JMRTD depends on; not needed at runtime
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            merges += "/META-INF/LICENSE.md"
        }
    }
    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(signingProperty("storeFile")!!)
                storePassword = signingProperty("storePassword")
                keyAlias = signingProperty("keyAlias")
                keyPassword = signingProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned when local.properties has no signing key; see the README
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
    testOptions {
        // Android framework calls (e.g. android.util.Log) return defaults in JVM unit tests
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

/** Copies files from the repository into one folder of the app's assets. */
abstract class BundleAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val files: ConfigurableFileCollection

    @get:Input
    abstract val folder: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile.resolve(folder.get())
        target.deleteRecursively()
        target.mkdirs()
        files.files.filter { it.isFile }.forEach { it.copyTo(target.resolve(it.name)) }
    }
}

// The merged measured profiles, which BundledProfiles installs at start-up. Only the shared profiles/*.json:
// profiles/pending/ is held back for the steward and never ships
val bundleMeasuredProfiles = tasks.register<BundleAssets>("bundleMeasuredProfiles") {
    files.from(rootProject.fileTree("profiles") { include("*.json") })
    folder.set("profiles")
}
// The licence and third-party notices, shown under Settings > About > Open-source licences
val bundleLicenceNotices = tasks.register<BundleAssets>("bundleLicenceNotices") {
    files.from(rootProject.files("LICENSE", "THIRD_PARTY_LICENSES.md"), rootProject.fileTree("licenses"))
    folder.set("licenses")
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleMeasuredProfiles, BundleAssets::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(bundleLicenceNotices, BundleAssets::outputDir)
    }
}

// Capture-mode maintainer tools (tools/capture-intake/, docs/lab-captures.md). They run on the unit-test classpath, because
// the hold-out rule and the fidelity diff need the JVM emulator harness, which is test code. Parameters come as project
// properties (-Pintake.input=...), so a path with spaces needs no quoting. None of them runs git.
fun registerCaptureTool(taskName: String, taskDescription: String, entryPoint: String, toolArgs: List<String>) {
    tasks.register<JavaExec>(taskName) {
        group = "capture"
        description = taskDescription
        // The classpath of the unit tests (compiled test classes, main classes, dependencies, the mockable android.jar);
        // using it as a file collection depends on compiling them, not on running them
        classpath = tasks.getByName<Test>("testDebugUnitTest").classpath
        mainClass.set(entryPoint)
        // Relative paths in the arguments are the caller's, not the module's
        workingDir = rootProject.projectDir
        args(toolArgs)
    }
}

fun gradleFlag(name: String, option: String): List<String> =
    if (providers.gradleProperty(name).orNull == "true") listOf(option) else emptyList()

fun gradleOption(name: String, option: String): List<String> =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotEmpty() }?.let { listOf(option, it) } ?: emptyList()

registerCaptureTool(
    "captureIntake",
    "Maintainer intake of contributed captures: lint, quarantine, store, merge into profiles/, diff (-Pintake.input, -Pintake.store, -Pintake.repo, -Pintake.apply=true, -Pintake.links=GEN=ID,...)",
    "com.hddev.passportemu.capture.intake.IntakeMain",
    gradleOption("intake.input", "--input") + gradleOption("intake.store", "--store") + gradleOption("intake.repo", "--repo") +
        gradleFlag("intake.apply", "--apply") +
        providers.gradleProperty("intake.links").orNull.orEmpty().split(',').filter { it.isNotBlank() }.flatMap { listOf("--link", it) }
)

registerCaptureTool(
    "captureExportPack",
    "Writes the command sheet of probe packs for a lab operator (-Pexport.packs=tier-a/1,..., -Pexport.out=FILE, -Pexport.reader=NAME, -Pexport.extended=false)",
    "com.hddev.passportemu.capture.transcript.LabMain",
    listOf("export") + gradleOption("export.packs", "--packs") + gradleOption("export.out", "--out") + gradleOption("export.reader", "--reader") +
        gradleOption("export.source", "--source") + (if (providers.gradleProperty("export.extended").orNull == "false") listOf("--no-extended") else emptyList())
)

registerCaptureTool(
    "captureImportTranscript",
    "Imports a PC/SC or Proxmark transcript as a capture JSON, linted (-Pimport.transcript=FILE, -Pimport.out=DIR, -Pimport.source=PCSC|PROXMARK, -Pimport.packs=tier-a/1,..., -Pimport.sacrificial=true)",
    "com.hddev.passportemu.capture.transcript.LabMain",
    listOf("import") + gradleOption("import.transcript", "--transcript") + gradleOption("import.out", "--out") + gradleOption("import.source", "--source") +
        gradleOption("import.packs", "--packs") + gradleFlag("import.sacrificial", "--sacrificial")
)
