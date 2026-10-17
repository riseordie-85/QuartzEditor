import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tim.quartzeditor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tim.quartzeditor"
        minSdk = 33 // Android 13+ (adjustments use Android 13 GPU shaders)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // The AI models are big and already compressed: keep them as they are in the app
    androidResources {
        noCompress += "onnx"
    }

    sourceSets["main"].assets.srcDir(rootProject.file("ai-models"))
}

// ===================================================================== on-device AI models
//
// Select Object (MobileSAM) and AI Remove (MI-GAN) run on the tablet with no internet. Their model
// files are downloaded here, once per computer, when the app is built (into ai-models/, which Git
// ignores and Clean leaves alone), and checked against their known SHA-256. Without internet the app still
// builds; those two features then say the model isn't included.
data class AiModel(val name: String, val url: String, val sha256: String)

val aiModels = listOf(
    AiModel(
        "mobile_sam_image_encoder.onnx",
        "https://huggingface.co/Acly/MobileSAM/resolve/0d3b403339b4674a82493d5e97964dd78089ddc8/mobile_sam_image_encoder.onnx",
        "580f5fb648ea1062c0aabc26217aed56921985f03f0cbbd852bba81d760cc749",
    ),
    AiModel(
        "sam_mask_decoder_single.onnx",
        "https://huggingface.co/Acly/MobileSAM/resolve/0d3b403339b4674a82493d5e97964dd78089ddc8/sam_mask_decoder_single.onnx",
        "93915fc7c993ab9d59ab8c9ccd3bce37f7509c81ab4150a74abd4d2abbd8570d",
    ),
    AiModel(
        "migan_pipeline_v2.onnx",
        "https://huggingface.co/andraniksargsyan/migan/resolve/1538c135034b8cfe7a8472f34d09c8a5a45b17a7/migan_pipeline_v2.onnx",
        "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b",
    ),
)

val downloadAiModels by tasks.registering {
    description = "Downloads the on-device AI models (once) into ai-models/models."
    val dir = rootProject.file("ai-models/models")
    val models = aiModels
    // Half-downloaded files wait outside the app's assets
    val tmpDir = rootProject.file(".ai-models-download")
    inputs.property("models", models.joinToString { it.name + "=" + it.sha256 })
    outputs.dir(dir)
    outputs.upToDateWhen { models.all { File(dir, it.name).isFile } }
    doLast {
        dir.mkdirs()
        fun sha(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        for (m in models) {
            val target = File(dir, m.name)
            if (target.isFile && sha(target) == m.sha256) continue
            tmpDir.mkdirs()
            val tmp = File(tmpDir, m.name + ".part")
            try {
                logger.lifecycle("Downloading AI model ${m.name}…")
                var url = URL(m.url)
                lateinit var conn: HttpURLConnection
                var hops = 0
                // Follow the download's redirects (Hugging Face sends you on to its file servers)
                while (true) {
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 30_000
                    conn.readTimeout = 120_000
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 8) {
                        url = URL(url, conn.getHeaderField("Location"))
                        conn.disconnect()
                        continue
                    }
                    if (code != 200) throw GradleException("HTTP $code")
                    break
                }
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val got = sha(tmp)
                if (got != m.sha256) {
                    tmp.delete()
                    throw GradleException("checksum didn't match ($got)")
                }
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                tmp.delete()
                logger.warn("Couldn't download AI model ${m.name}: ${e.message}. The app builds without it (Select Object / AI Remove will say so).")
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadAiModels) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Gallery thumbnails on the launch screen
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Background removal (on-device, delivered through Google Play services)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    // Face-aware Liquify (runs in Google Play services, like subject segmentation)
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")

    // Runs the bundled AI models (Select Object, AI Remove) on the device
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
