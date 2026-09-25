import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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
