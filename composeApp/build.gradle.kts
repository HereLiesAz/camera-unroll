import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.application)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    jvm("desktop")

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser { commonWebpackConfig { outputFileName = "composeApp.js" } }
        binaries.executable()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Skia-backed targets share one codec implementation.
        val skiaMain by creating { dependsOn(commonMain.get()) }
        val desktopMain by getting {
            dependsOn(skiaMain)
            // The bundled depth network: one copy on disk, on the classpath here.
            resources.srcDir("models")
        }
        iosMain.get().dependsOn(skiaMain)
        wasmJsMain.get().dependsOn(skiaMain)
        // Targets with a real filesystem share the save-dialog implementation.
        val fileSystemMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(fileSystemMain)
        desktopMain.dependsOn(fileSystemMain)
        iosMain.get().dependsOn(fileSystemMain)

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.filekit.dialogs.compose)
        }
        wasmJsMain {
            // The same bundled depth network, served beside the page.
            resources.srcDir("models")
            dependencies {
                implementation(npm("onnxruntime-web", "1.20.1"))
                implementation(devNpm("copy-webpack-plugin", "12.0.2"))
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation("com.github.HereLiesAz.AzNavRail:aznavrail-updates-compat:12.5")
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.onnxruntime.android)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.onnxruntime)
        }
    }
}

// Versioning follows the HereLiesAz/workflows contract (docs/RELEASE_VERSIONING.md there).
// version.properties holds the hand-managed major.minor.patch and what the last release
// recorded. A central release passes the next versionCode as -PversionCode /
// -PversionCodeOverride and the name as -PversionName; nothing is derived or multiplied here.
val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
fun recorded(key: String) = appVersion.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
fun passed(vararg names: String) = names.firstNotNullOfOrNull { (findProperty(it) as String?)?.trim()?.takeIf(String::isNotEmpty) }
val appVersionCode = (passed("versionCodeOverride", "versionCode") ?: recorded("versionCode"))?.toIntOrNull()?.coerceAtLeast(1) ?: 1
val appVersionName = passed("versionNameOverride", "versionName") ?: recorded("versionName")
    ?: listOf("versionMajor", "versionMinor", "versionPatch", "versionBuild").joinToString(".") { recorded(it) ?: "0" }

android {
    namespace = "com.hereliesaz.cameraunroll"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.hereliesaz.cameraunroll"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        // Phones (and the x86_64 emulator); the depth runtime ships a native library per ABI.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    // The bundled depth network, read from assets (the same file the web and desktop use).
    sourceSets["main"].assets.srcDir("models")
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    // The model is already compressed weights; storing it lets it be read without inflating.
    androidResources { noCompress += "onnx" }
}

compose.desktop {
    application {
        mainClass = "com.hereliesaz.cameraunroll.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CameraUnroll"
            packageVersion = "1.0.0"
        }
    }
}
