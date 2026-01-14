你想要在 Android 项目中集成 sherpa-onnx，这个库主要用于实现离线语音识别、语音合成等功能，我会一步步带你完成整个集成流程，确保你能在 Android 项目中成功使用它。

### 一、集成前的准备

#### 1. 环境要求

- Android Studio 版本 >= Arctic Fox (2020.3.1)
- Android NDK 版本 >= 21.4.7075529（sherpa-onnx 依赖 NDK 编译 native 代码）
- 最低 Android SDK 版本 >= 21 (Android 5.0)

#### 2. 下载必要文件

首先从 sherpa-onnx 的官方仓库获取 Android 相关资源：

1. 克隆仓库：
   1. ```Bash
      git clone https://github.com/k2-fsa/sherpa-onnx.git
      ```
2. 进入 `sherpa-onnx/android` 目录，这里包含了预编译的 aar 库、示例代码和模型文件。

### 二、集成步骤（以语音识别为例）

#### 步骤 1：导入 aar 库到 Android 项目

1. 在你的 Android 项目中，创建 `app/libs` 目录（如果没有）。
2. 将 `sherpa-onnx/android/sherpa-onnx/build/outputs/aar/sherpa-onnx-release.aar` 复制到 `libs` 目录。
3. 修改 `app/build.gradle`（Module 级），添加依赖和仓库配置：
   1. ```Plain
      plugins {
          id 'com.android.application'
          // 如果用 Kotlin 需添加
          id 'org.jetbrains.kotlin.android'
      }
      
      android {
          namespace "com.your.package.name"
          compileSdk 34
      
          defaultConfig {
              applicationId "com.your.package.name"
              minSdk 21
              targetSdk 34
              versionCode 1
              versionName "1.0"
      
              testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
              // 配置 NDK 架构（按需选择，armeabi-v7a/arm64-v8a/x86/x86_64）
              ndk {
                  abiFilters 'arm64-v8a', 'armeabi-v7a'
              }
          }
      
          buildTypes {
              release {
                  minifyEnabled false
                  proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
              }
          }
          compileOptions {
              sourceCompatibility JavaVersion.VERSION_1_8
              targetCompatibility JavaVersion.VERSION_1_8
          }
          kotlinOptions {
              jvmTarget = '1.8'
          }
          // 引入本地 aar 库
          repositories {
              flatDir {
                  dirs 'libs'
              }
          }
      }
      
      dependencies {
          // sherpa-onnx 依赖
          implementation(name: 'sherpa-onnx-release', ext: 'aar')
          // 基础依赖
          implementation 'androidx.core:core-ktx:1.12.0'
          implementation 'androidx.appcompat:appcompat:1.6.1'
          implementation 'com.google.android.material:material:1.11.0'
          testImplementation 'junit:junit:4.13.2'
          androidTestImplementation 'androidx.test.ext:junit:1.1.5'
          androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
      }
      ```
4. 同步 Gradle（点击 Sync Now）。

#### 步骤 2：准备语音识别模型

sherpa-onnx 依赖预训练的模型文件，以轻量级的 `sherpa-onnx-streaming-zipformer-small-en` 为例：

1. 从 [sherpa-onnx 模型仓库](https://github.com/k2-fsa/sherpa-onnx/releases) 下载对应模型（选择 Android 兼容的版本）。
2. 解压后，将模型文件（如 `encoder.onnx`、`decoder.onnx`、`joiner.onnx`、`tokens.txt`）复制到 Android 项目的 `app/src/main/assets` 目录（没有 assets 目录则手动创建）。

#### 步骤 3：编写 Android 端调用代码（Kotlin 示例）

以下是一个简单的离线语音识别示例，实现“读取音频文件 → 调用 sherpa-onnx → 获取识别结果”：

```Kotlin
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.k2fsa.sherpa.onnx.SherpaOnnx
import com.k2fsa.sherpa.onnx.SherpaOnnxConfig
import com.k2fsa.sherpa.onnx.SherpaOnnxResult
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private val TAG = "SherpaOnnxDemo"
    private lateinit var sherpaOnnx: SherpaOnnx

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 初始化 SherpaOnnx 配置
        val config = SherpaOnnxConfig().apply {
            // 模型路径（从 assets 复制到本地目录，因为 assets 不能直接被 native 层访问）
            val modelDir = copyAssetsToCache(this@MainActivity, "model")
            encoder = "$modelDir/encoder.onnx"
            decoder = "$modelDir/decoder.onnx"
            joiner = "$modelDir/joiner.onnx"
            tokens = "$modelDir/tokens.txt"

            // 识别参数配置
            numThreads = 2 // 线程数，根据设备调整
            decodingMethod = "greedy_search" // 解码方式：greedy_search/modified_beam_search
            maxActivePaths = 4 // beam search 时的路径数
            enableEndpoint = true // 启用端点检测
            rule1MinTrailingSilence = 1.2f
            rule2MinTrailingSilence = 2.4f
            rule3MinUtteranceLength = 20.0f
        }

        // 2. 初始化 SherpaOnnx 实例
        try {
            sherpaOnnx = SherpaOnnx(config)
            Log.d(TAG, "SherpaOnnx 初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "SherpaOnnx 初始化失败：${e.message}")
            return
        }

        // 3. 识别本地音频文件（示例：assets 中的 test.wav，16kHz/单声道/16bit PCM）
        val audioFile = copyAssetsToCache(this, "test.wav")
        recognizeAudioFile(audioFile)
    }

    /**
     * 将 assets 中的文件复制到缓存目录（native 层无法直接访问 assets）
     */
    private fun copyAssetsToCache(context: Context, assetName: String): String {
        val cacheFile = File(context.cacheDir, assetName)
        if (cacheFile.exists()) return cacheFile.absolutePath

        context.assets.open(assetName).use { inputStream ->
            FileOutputStream(cacheFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return cacheFile.absolutePath
    }

    /**
     * 识别音频文件
     */
    private fun recognizeAudioFile(audioPath: String) {
        // 注意：音频需满足 16kHz 采样率、单声道、16bit 有符号 PCM 格式
        val result: SherpaOnnxResult? = try {
            sherpaOnnx.decodeFile(audioPath)
        } catch (e: Exception) {
            Log.e(TAG, "识别失败：${e.message}")
            null
        }

        result?.let {
            Log.d(TAG, "识别结果：${it.text}")
            // 逐字结果（带时间戳）
            it.tokens.forEachIndexed { index, token ->
                Log.d(TAG, "第${index+1}个token：$token (开始时间：${it.timestamps[index]}s)")
            }
        } ?: Log.d(TAG, "无识别结果")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放资源
        sherpaOnnx.release()
    }
}
```

#### 步骤 4：添加权限（可选）

如果需要录制音频进行实时识别，需在 `AndroidManifest.xml` 中添加权限：

```XML
<manifest ...>
    <!-- 录音权限 -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <!-- 文件读写权限（如需读取本地音频） -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    
    <!-- 声明使用麦克风（Android 12+ 需添加） -->
    <uses-feature android:name="android.hardware.microphone" android:required="false" />
    
    <application ...>
        ...
    </application>
</manifest>
```

注意：Android 6.0+ 需动态申请录音/文件权限，可补充权限申请代码。

### 三、关键说明

1. **模型兼容性**：
   1. 确保下载的模型与 sherpa-onnx 版本匹配，优先选择官方标注“Android”的模型。
   2. 不同语言的模型（中/英/多语言）配置方式一致，仅需替换模型文件和 `tokens.txt`。
2. **实时语音识别**：

1. 上述示例是离线文件识别，如需实时录音识别，可使用 sherpa-onnx 提供的 `SherpaOnnxStream` 类，结合 Android 的 `AudioRecord` 录制音频流，逐帧喂给模型解码。

1. **常见问题**：
   1. 报错“找不到 native 库”：检查 `abiFilters` 是否包含设备架构，或替换预编译的 aar 库。
   2. 识别无结果：检查音频格式（必须是 16kHz/单声道/16bit PCM），或更换模型。

### 总结

1. 集成 sherpa-onnx 的核心是导入预编译的 aar 库，并配置 NDK 架构支持；
2. 模型文件需从 assets 复制到本地目录（native 层无法直接访问 assets）；
3. 调用时需先初始化 `SherpaOnnxConfig`，再创建 `SherpaOnnx` 实例，最后调用解码接口完成语音识别。

如果需要语音合成（TTS）功能，集成流程类似，只需替换模型（选择 TTS 模型）和调用对应的合成接口（`SherpaOnnxTts` 类）即可。