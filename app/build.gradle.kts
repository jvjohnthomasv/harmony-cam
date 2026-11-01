import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.harmonycam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.harmonycam.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a") // all phones running Android 10+ in practice
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    signingConfigs {
        // Side-load key committed with the project so every build can update the previous install.
        // Replace with your own private key before publishing to the Play Store.
        create("sideload") {
            storeFile = file("harmonycam-sideload.jks")
            storePassword = "harmonycam"
            keyAlias = "harmonycam"
            keyPassword = "harmonycam"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    androidResources {
        // MediaPipe memory-maps its model straight from the APK, so it must stay uncompressed.
        // (The speech model is copied out to storage on first use, so it can be compressed.)
        noCompress += listOf("task", "tflite")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        // compressed native libs & dex: much smaller download (extracted once at install)
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    implementation("com.google.oboe:oboe:1.9.3")

    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}

// ───────────── ML models: downloaded once at build time and packed into the APK ─────────────
val modelAssetsDir = layout.projectDirectory.dir("src/main/assets")

val downloadModels by tasks.registering {
    description = "Downloads the hand-tracking and offline speech models into assets/"
    val handModel = modelAssetsDir.file("hand_landmarker.task").asFile
    val voskDir = modelAssetsDir.dir("model-en-us").asFile
    outputs.file(handModel)
    outputs.dir(voskDir)
    doLast {
        if (!handModel.exists() || handModel.length() < 1000) {
            handModel.parentFile.mkdirs()
            logger.lifecycle("Downloading MediaPipe hand landmarker model…")
            URI("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task")
                .toURL().openStream().use { input -> handModel.outputStream().use { input.copyTo(it) } }
        }
        if (!File(voskDir, "uuid").exists()) {
            logger.lifecycle("Downloading Vosk English model…")
            voskDir.deleteRecursively()
            voskDir.mkdirs()
            val url = URI("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").toURL()
            ZipInputStream(url.openStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    // strip the top-level "vosk-model-small-en-us-0.15/" folder
                    val rel = entry.name.substringAfter('/', "")
                    if (rel.isNotEmpty()) {
                        val out = File(voskDir, rel)
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            File(voskDir, "uuid").writeText("vosk-model-small-en-us-0.15\n")
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModels) }
