package com.aotuman.baobaoai.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object SherpaVadManager {
    private const val TAG = "SherpaVadManager"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
    
    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null
    
    // 模型状态管理
    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotInitialized)
    val modelState: StateFlow<ModelState> = _modelState

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Buffer to hold the entire utterance for offline recognition
    private val audioBuffer = ArrayList<Float>()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    // 错误码定义
    object ErrorCode {
        const val NO_PERMISSION = 1001
        const val INIT_FAILED = 1002
        const val RECORDING_FAILED = 1003
        const val MODEL_LOAD_FAILED = 1004
    }

    // 模型状态密封类
    sealed class ModelState {
        object NotInitialized : ModelState()
        object Loading : ModelState()
        object Ready : ModelState()
        data class Error(val message: String) : ModelState()
    }

    suspend fun initialize(context: Context) {
        // 检查是否已经初始化
        if (vad != null && recognizer != null) {
            _modelState.value = ModelState.Ready
            return
        }

        _modelState.value = ModelState.Loading

        withContext(Dispatchers.IO) {
            try {
                initVadModel(context)
                initSenseVoiceModel(context)
                
                // 验证两个模型是否都初始化成功
                if (vad != null && recognizer != null) {
                    Log.i(TAG, "所有模型初始化成功")
                    _modelState.value = ModelState.Ready
                } else {
                    _modelState.value = ModelState.Error("部分模型初始化失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "模型初始化失败", e)
                _modelState.value = ModelState.Error(e.message ?: "Unknown error during initialization")
            }
        }
    }

    private fun initVadModel(context: Context) {
        if (vad != null) {
            return
        }

        val modelDir = File(context.filesDir, "sherpa-model/vad")
        if (!modelDir.exists()) modelDir.mkdirs()

        // 模型文件列表
        val modelName = "silero_vad.onnx"

        // 复制所有必需的模型文件到内部存储
        val modelFile = File(modelDir, modelName)

        AssetUtil.copyAsset(context, "sherpa-model/vad/$modelName", modelFile)

        // 验证所有文件是否存在且有效
        if (!modelFile.exists()) {
            throw Exception("VAD model files missing or invalid in internal storage")
        }

        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelFile.absolutePath,
                threshold = 0.5F,
                minSilenceDuration = 0.25F,
                minSpeechDuration = 0.25F,
                windowSize = 512,
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
        )

        vad = Vad(
            assetManager = null,
            config = config,
        )

        Log.i(TAG, "AVD模型初始化成功")
    }

    private fun initSenseVoiceModel(context: Context) {
        if (recognizer != null) {
            return
        }

        val modelDir = File(context.filesDir, "sherpa-model/sense-voice")
        if (!modelDir.exists()) modelDir.mkdirs()

        val modelName = "model.int8.onnx"
        val tokensName = "tokens.txt"

        val modelFile = File(modelDir, modelName)
        val tokensFile = File(modelDir, tokensName)

        // Ensure files are copied and valid
        AssetUtil.copyAsset(context, "sherpa-model/sense-voice/$modelName", modelFile)
        AssetUtil.copyAsset(context, "sherpa-model/sense-voice/$tokensName", tokensFile)

        if (!modelFile.exists() || !tokensFile.exists() || modelFile.length() == 0L || tokensFile.length() == 0L) {
            throw Exception("SenseVoice model files missing or invalid in internal storage")
        }

        val config = OfflineRecognizerConfig(
            featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelFile.absolutePath,
                    language = "" // Auto detect
                ),
                tokens = tokensFile.absolutePath,
                debug = true,
                numThreads = 1,
                modelType = "sense_voice"
            )
        )

        // Pass assetManager = null to force loading from file paths (filesDir)
        // If we pass context.assets, Sherpa tries to load paths relative to assets
        recognizer = OfflineRecognizer(assetManager = null, config = config)
        Log.d(TAG, "Sherpa SenseVoice initialized successfully")
    }

    @SuppressLint("MissingPermission")
    fun startListening(
        onResultCallback: (String) -> Unit,
        onErrorCallback: (String) -> Unit
    ) {
        val modelState = modelState.value
        if (vad == null || recognizer == null) {
            if (modelState is ModelState.Error) {
                onErrorCallback("Model Error: ${modelState.message}")
            } else {
                onErrorCallback("Model not loaded yet")
            }
            return
        }

        if (_isListening.value) return

        onResult = onResultCallback
        onError = onErrorCallback
        audioBuffer.clear()
        vad?.reset()

        try {
            if (!hasRecordAudioPermission()) {
                onErrorCallback("RECORD_AUDIO 权限未授予")
                return
            }

            audioRecord = createAudioRecordOrNull()
            if (audioRecord == null) {
                onErrorCallback("AudioRecord 初始化失败（设备不支持当前录音参数或录音被系统限制）")
                return
            }

            audioRecord?.startRecording()
            _isListening.value = true

            startRecordingLoop()

        } catch (e: Exception) {
            e.printStackTrace()
            _isListening.value = false
            onErrorCallback(e.message ?: "Failed to start recording")
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        // todo
        return true
//        return context?.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecordOrNull(): AudioRecord? {
        // 常见设备可用组合：不同音频源/采样率兜底
        val audioSources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        for (source in audioSources) {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            if (minBufferSize <= 0) {
                Log.w(TAG, "getMinBufferSize failed: source=$source sr=$SAMPLE_RATE ret=$minBufferSize")
                continue
            }
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBufferSize * 2
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "AudioRecord initialized: source=$source sr=$SAMPLE_RATE buffer=${minBufferSize * 2}")
                    return record
                } else {
                    Log.w(TAG, "AudioRecord not initialized: source=$source sr=$SAMPLE_RATE state=${record.state}")
                    record.release()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "AudioRecord create failed: source=$source sr=$SAMPLE_RATE err=${t.message}", t)
            }
        }
        return null
    }

    private fun startRecordingLoop() {
        recordingJob = scope.launch {
            val bufferSize = 512 // in samples
            val buffer = ShortArray(bufferSize)
            while (_isListening.value) {
                val ret = audioRecord?.read(buffer, 0, buffer.size)
                if (ret != null && ret > 0) {
                    val samples = FloatArray(ret) { buffer[it] / 32768.0f }

                    if (vad == null) continue
                    val vad = vad!!
                    vad.acceptWaveform(samples)
                    while(!vad.empty()) {
                        val segment = vad.front()
                        CoroutineScope(Dispatchers.IO).launch {
                            val text = runSecondPass(segment.samples)
                            if (text.isNotBlank()) {
//                                Log.e(TAG, "runSecondPass Text: $text")
                                onResult?.invoke(text)
                            }
                        }

                        vad.pop()
                    }

                    val isSpeechDetected = vad.isSpeechDetected()
//                    Log.e(TAG, "isSpeechDetected: $isSpeechDetected")
                }
            }
        }
    }

    suspend fun stopListening() {
        if (!_isListening.value) return

        _isListening.value = false

        try {
            recordingJob?.cancel()
            recordingJob?.join() // Wait for loop to finish

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun runSecondPass(samples: FloatArray): String {
        if (recognizer == null) return ""
        val recognizer = recognizer!!
        val stream = recognizer.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        stream.release()
        return result.text
    }
}