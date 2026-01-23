import java.io.FileOutputStream
import java.net.URL
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Load local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
val defaultApiKey = localProperties.getProperty("ZHIPU_API_KEY") ?: ""

data class ModelInfo(
    val url: String,
    val relativePath: String // 相对于 baseOutputDir 的完整路径
)

tasks.register("downloadModels") {
    val models = listOf(
        ModelInfo("https://github.com/michael19900101/BaoBaoAI/releases/download/SherpaModel/decoder-epoch-99-avg-1-chunk-16-left-64.onnx", "kws/decoder-epoch-99-avg-1-chunk-16-left-64.onnx"),
        ModelInfo("https://github.com/michael19900101/BaoBaoAI/releases/download/SherpaModel/encoder-epoch-99-avg-1-chunk-16-left-64.onnx", "kws/encoder-epoch-99-avg-1-chunk-16-left-64.onnx"),
        ModelInfo("https://github.com/michael19900101/BaoBaoAI/releases/download/SherpaModel/joiner-epoch-99-avg-1-chunk-16-left-64.onnx", "kws/joiner-epoch-99-avg-1-chunk-16-left-64.onnx"),
        ModelInfo("https://github.com/michael19900101/BaoBaoAI/releases/download/SherpaModel/model.int8.onnx", "sense-voice/model.int8.onnx"),
        ModelInfo("https://github.com/michael19900101/BaoBaoAI/releases/download/SherpaModel/silero_vad.onnx","vad/silero_vad.onnx"),
        ModelInfo("https://github.com/michael19900101/BaoBaoAI/releases/download/SherpaModel/decoder-epoch-99-avg-1.onnx", "streaming/decoder-epoch-99-avg-1.onnx"),
        ModelInfo("https://github.com/michael19900101/BaoBaoAI/releases/download/SherpaModel/encoder-epoch-99-avg-1.onnx", "streaming/encoder-epoch-99-avg-1.onnx"),
        ModelInfo("https://github.com/michael19900101/BaoBaoAI/releases/download/SherpaModel/joiner-epoch-99-avg-1.onnx", "streaming/joiner-epoch-99-avg-1.onnx"),
    )
    val baseOutputDir = project.file("src/main/assets/sherpa-model")

    doLast {
        if (!baseOutputDir.exists()) {
            baseOutputDir.mkdirs()
        }

        models.forEach { model ->
            val outputFile = File(baseOutputDir, model.relativePath)

            // 确保父目录存在
            outputFile.parentFile?.let { parentDir ->
                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                    println("Created directory: ${parentDir.absolutePath}")
                }
            }

            if (!outputFile.exists()) {
                println("Downloading ${model.relativePath} from ${model.url}...")
                try {
                    val url = URL(model.url)
                    val connection = url.openConnection()
                    connection.connect()
                    connection.getInputStream().use { inputStream ->
                        FileOutputStream(outputFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    println("Download complete: ${outputFile.absolutePath}")
                } catch (e: Exception) {
                    println("Error downloading ${model.relativePath}: ${e.message}")
                    throw e
                }
            } else {
                println("${model.relativePath} already exists. Skipping download.")
            }
        }
    }
}

// 让 downloadModels 任务在编译时自动执行
tasks.preBuild {
    dependsOn("downloadModels")
}

android {
    namespace = "com.aotuman.baobaoai"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.aotuman.baobaoai"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_API_KEY", "\"$defaultApiKey\"")
    }

    buildTypes {
        debug {
            buildConfigField("Boolean", "AUTO_INPUT_DEV_MODE", "false")
        }
        release {
            buildConfigField("Boolean", "AUTO_INPUT_DEV_MODE", "false")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libonnxruntime.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Material 图标扩展，提供 Icons.Filled.TouchApp / Gesture / Swipe / Keyboard 等
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(files("libs/sherpa-onnx-1.12.20.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.sidhu.autoinput:library:1.1.1")
}