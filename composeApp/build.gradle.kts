import org.jetbrains.compose.desktop.application.dsl.TargetFormat
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
            languageSettings.optIn("androidx.compose.ui.test.ExperimentalTestApi")
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
            implementation("io.mockk:mockk:1.13.8")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
        }
    }
}

// Keep generated resource imports stable when the app's display name changes.
compose.resources {
    packageOfResClass = "smartemu.composeapp.generated.resources"
}

// The dev signing key's location and passwords, from local.properties; see keystore/
val devSigning = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use(::load)
}

android {
    namespace = "com.hddev.smartemu"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.hddev.smartemu"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    // Test Document Signer that signs EF.SOD; see test-pki/README.md
    sourceSets["main"].resources.srcDir(rootProject.file("test-pki/document-signer"))
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Duplicated by the BouncyCastle jars JMRTD depends on; not needed at runtime
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            merges += "/META-INF/LICENSE.md"
        }
    }
    signingConfigs {
        create("dev") {
            devSigning.getProperty("smartemu.signing.storeFile")?.let { storeFile = rootProject.file(it) }
            storePassword = devSigning.getProperty("smartemu.signing.storePassword")
            keyAlias = devSigning.getProperty("smartemu.signing.keyAlias")
            keyPassword = devSigning.getProperty("smartemu.signing.keyPassword")
        }
    }
    buildTypes {
        getByName("debug") {
            if (providers.gradleProperty("recordingFixtures").orNull == "true") {
                applicationIdSuffix = ".recording"
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Shared as a test build, so signed with the dev key rather than a store release key
            signingConfig = signingConfigs.getByName("dev")
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
    "com.hddev.smartemu.capture.intake.IntakeMain",
    gradleOption("intake.input", "--input") + gradleOption("intake.store", "--store") + gradleOption("intake.repo", "--repo") +
        gradleFlag("intake.apply", "--apply") +
        providers.gradleProperty("intake.links").orNull.orEmpty().split(',').filter { it.isNotBlank() }.flatMap { listOf("--link", it) }
)

registerCaptureTool(
    "captureExportPack",
    "Writes the command sheet of probe packs for a lab operator (-Pexport.packs=tier-a/1,..., -Pexport.out=FILE, -Pexport.reader=NAME, -Pexport.extended=false)",
    "com.hddev.smartemu.capture.transcript.LabMain",
    listOf("export") + gradleOption("export.packs", "--packs") + gradleOption("export.out", "--out") + gradleOption("export.reader", "--reader") +
        gradleOption("export.source", "--source") + (if (providers.gradleProperty("export.extended").orNull == "false") listOf("--no-extended") else emptyList())
)

registerCaptureTool(
    "captureImportTranscript",
    "Imports a PC/SC or Proxmark transcript as a capture JSON, linted (-Pimport.transcript=FILE, -Pimport.out=DIR, -Pimport.source=PCSC|PROXMARK, -Pimport.packs=tier-a/1,..., -Pimport.sacrificial=true)",
    "com.hddev.smartemu.capture.transcript.LabMain",
    listOf("import") + gradleOption("import.transcript", "--transcript") + gradleOption("import.out", "--out") + gradleOption("import.source", "--source") +
        gradleOption("import.packs", "--packs") + gradleFlag("import.sacrificial", "--sacrificial")
)
