package com.aotuman.baobaoai.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getEndpointConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object SherpaStreamingManager {
    private const val TAG = "SherpaStreamingManager"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
    private var idx: Int = 0
    private var recognizer: OnlineRecognizer? = null
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

    private var listener: StreamingDetectionListener? = null
    
    interface StreamingDetectionListener {
        fun onDetected(isEndpoint: Boolean, text: String)
        fun onError(errorCode: Int, errorMessage: String)
    }

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
        if (recognizer != null) {
            _modelState.value = ModelState.Ready
            return
        }
        _modelState.value = ModelState.Loading

        withContext(Dispatchers.IO) {
            try {
                val modelDir = File(context.filesDir, "sherpa-model/streaming")
                if (!modelDir.exists()) modelDir.mkdirs()

                // 模型文件列表
                val encoderName = "encoder-epoch-99-avg-1.onnx"
                val decoderName = "decoder-epoch-99-avg-1.onnx"
                val joinerName = "joiner-epoch-99-avg-1.onnx"
                val tokensName = "tokens.txt"

                // 复制所有必需的模型文件到内部存储
                val encoderFile = File(modelDir, encoderName)
                val decoderFile = File(modelDir, decoderName)
                val joinerFile = File(modelDir, joinerName)
                val tokensFile = File(modelDir, tokensName)

                AssetUtil.copyAsset(context, "sherpa-model/streaming/$encoderName", encoderFile)
                AssetUtil.copyAsset(context, "sherpa-model/streaming/$decoderName", decoderFile)
                AssetUtil.copyAsset(context, "sherpa-model/streaming/$joinerName", joinerFile)
                AssetUtil.copyAsset(context, "sherpa-model/streaming/$tokensName", tokensFile)

                // 验证所有文件是否存在且有效
                if (!encoderFile.exists() || !decoderFile.exists() || !joinerFile.exists() ||
                    !tokensFile.exists() ||
                    encoderFile.length() == 0L || decoderFile.length() == 0L || joinerFile.length() == 0L ||
                    tokensFile.length() == 0L
                ) {
                    _modelState.value =
                        ModelState.Error("Model files missing or invalid in internal storage")
                    return@withContext
                }

                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoderFile.absolutePath,
                        decoder = decoderFile.absolutePath,
                        joiner = joinerFile.absolutePath
                    ),
                    tokens = tokensFile.absolutePath,
                    modelType = "zipformer",
                )

                val config = OnlineRecognizerConfig(
                    featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    endpointConfig = getEndpointConfig(),
                    enableEndpoint = true,
                )

                recognizer = OnlineRecognizer(
                    assetManager = null,
                    config = config
                )

                Log.i(TAG, "Streaming模型初始化成功")
                _modelState.value = ModelState.Ready
            } catch (e: Exception) {
                Log.e(TAG, "Streaming模型初始化失败", e)
                _modelState.value =
                    ModelState.Error(e.message ?: "Unknown error during initialization")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(
        context: Context,
        listener: StreamingDetectionListener
    ) {
        if (recognizer == null) {
            listener.onError(ErrorCode.MODEL_LOAD_FAILED, "Model loaded failed")
            return
        }
        if (_isListening.value) return

        this.listener = listener

        audioBuffer.clear()

        // 检查权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            listener.onError(ErrorCode.NO_PERMISSION, "没有录音权限")
            return
        }

        try {
            audioRecord = createAudioRecordOrNull()

            if (audioRecord == null) {
                listener.onError(ErrorCode.RECORDING_FAILED,"AudioRecord 初始化失败（设备不支持当前录音参数或录音被系统限制）")
                return
            }

            audioRecord?.startRecording()
            _isListening.value = true

            startRecordingLoop()
        } catch (e: Exception) {
            e.printStackTrace()
            _isListening.value = false
            listener.onError(ErrorCode.RECORDING_FAILED,e.message ?: "Failed to start recording")
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecordOrNull(): AudioRecord? {
        try {
            val numBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            Log.i(
                TAG, "buffer size in milliseconds: ${numBytes * 1000.0f / SAMPLE_RATE}"
            )

            return AudioRecord(
                AUDIO_SOURCE,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                numBytes * 2 // a sample has two bytes as we are using 16-bit PCM
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun startRecordingLoop() {
        recordingJob = scope.launch {
            if (recognizer == null) return@launch
            val recognizer = recognizer!!
            val stream = recognizer.createStream()

            val interval = 0.1 // i.e., 100 ms
            val bufferSize = (interval * SAMPLE_RATE).toInt() // in samples
            val buffer = ShortArray(bufferSize)
            
            while (_isListening.value) {
                val ret = audioRecord?.read(buffer, 0, buffer.size)
                if (ret != null && ret > 0) {
                    val samples = FloatArray(ret) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
                    while (recognizer.isReady(stream)) {
                        recognizer.decode(stream)
                    }

                    val isEndpoint = recognizer.isEndpoint(stream)
                    var text = recognizer.getResult(stream).text

                    // For streaming parformer, we need to manually add some
                    // paddings so that it has enough right context to
                    // recognize the last word of this segment
                    if (isEndpoint && recognizer.config.modelConfig.paraformer.encoder.isNotBlank()) {
                        val tailPaddings = FloatArray((0.8 * SAMPLE_RATE).toInt())
                        stream.acceptWaveform(tailPaddings, sampleRate = SAMPLE_RATE)
                        while (recognizer.isReady(stream)) {
                            recognizer.decode(stream)
                        }
                        text = recognizer.getResult(stream).text
                    }

                    if (text.isNotBlank()) {
                        listener?.onDetected(isEndpoint, text)
                    }

                    if (isEndpoint) {
                        recognizer.reset(stream)
                    }
                }
            }
            stream.release()
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
}