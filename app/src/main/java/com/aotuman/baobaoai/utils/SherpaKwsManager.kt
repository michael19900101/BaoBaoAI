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
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 语音唤醒插件管理器
 * 提供开始监听和停止监听麦克风的功能
 */
object SherpaKwsManager {
    private const val TAG = "SherpaKwsManager"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC

    // 模型状态管理
    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotInitialized)
    val modelState: StateFlow<ModelState> = _modelState

    // 关键词检测监听器
    interface KeywordDetectionListener {
        fun onKeywordDetected(keyword: String)
        fun onError(errorCode: Int, errorMessage: String)
    }

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

    private var kws: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var listener: KeywordDetectionListener? = null

    @Volatile
    private var isRecording = false

    /**
     * 初始化KWS模型
     * @param context 上下文
     */
    suspend fun initialize(context: Context) {
        if (kws != null) {
            _modelState.value = ModelState.Ready
            return
        }

        _modelState.value = ModelState.Loading

        withContext(Dispatchers.IO) {
            try {
                val modelDir = File(context.filesDir, "sherpa-model/kws")
                if (!modelDir.exists()) modelDir.mkdirs()

                // 模型文件列表
                val encoderName = "encoder-epoch-99-avg-1-chunk-16-left-64.onnx"
                val decoderName = "decoder-epoch-99-avg-1-chunk-16-left-64.onnx"
                val joinerName = "joiner-epoch-99-avg-1-chunk-16-left-64.onnx"
                val tokensName = "tokens.txt"
                val keywordsName = "keywords.txt"

                // 复制所有必需的模型文件到内部存储
                val encoderFile = File(modelDir, encoderName)
                val decoderFile = File(modelDir, decoderName)
                val joinerFile = File(modelDir, joinerName)
                val tokensFile = File(modelDir, tokensName)
                val keywordsFile = File(modelDir, keywordsName)

                copyAsset(context, "sherpa-model/kws/$encoderName", encoderFile)
                copyAsset(context, "sherpa-model/kws/$decoderName", decoderFile)
                copyAsset(context, "sherpa-model/kws/$joinerName", joinerFile)
                copyAsset(context, "sherpa-model/kws/$tokensName", tokensFile)
                copyAsset(context, "sherpa-model/kws/$keywordsName", keywordsFile)

                // 验证所有文件是否存在且有效
                if (!encoderFile.exists() || !decoderFile.exists() || !joinerFile.exists() || 
                    !tokensFile.exists() || !keywordsFile.exists() ||
                    encoderFile.length() == 0L || decoderFile.length() == 0L || joinerFile.length() == 0L ||
                    tokensFile.length() == 0L || keywordsFile.length() == 0L) {
                    _modelState.value = ModelState.Error("Model files missing or invalid in internal storage")
                    return@withContext
                }

                // 创建模型配置
                val transducerConfig = OnlineTransducerModelConfig(
                    encoder = encoderFile.absolutePath,
                    decoder = decoderFile.absolutePath,
                    joiner = joinerFile.absolutePath
                )

                val modelConfig = OnlineModelConfig(
                    transducer = transducerConfig,
                    paraformer = OnlineParaformerModelConfig(),
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(),
                    tokens = tokensFile.absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "zipformer2",
                    modelingUnit = "cjkchar",
                    bpeVocab = ""
                )

                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = 80,
                        dither = 0.0f
                    ),
                    modelConfig = modelConfig,
                    keywordsFile = keywordsFile.absolutePath,
                    maxActivePaths = 4,
                    keywordsScore = 1.0f,
                    keywordsThreshold = 0.25f,
                    numTrailingBlanks = 1
                )

                // 初始化关键词检测器，使用null作为assetManager，强制从文件路径加载
                kws = KeywordSpotter(
                    assetManager = null,
                    config = config
                )

                stream = kws?.createStream()
                Log.i(TAG, "KWS模型初始化成功")
                _modelState.value = ModelState.Ready

            } catch (e: Exception) {
                Log.e(TAG, "KWS模型初始化失败", e)
                _modelState.value = ModelState.Error(e.message ?: "Unknown error during initialization")
            }
        }
    }

    /**
     * 复制assets中的文件到内部存储
     */
    private fun copyAsset(context: Context, assetPath: String, outFile: File) {
        try {
            // 检查文件是否存在且有内容
            if (outFile.exists() && outFile.length() > 0) {
                return
            }

            Log.d(TAG, "Copying asset $assetPath to ${outFile.absolutePath}")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset: $assetPath", e)
            // 删除可能的部分文件
            if (outFile.exists()) {
                outFile.delete()
            }
        }
    }

    /**
     * 开始监听麦克风
     * @param context 上下文
     * @param listener 关键词检测监听器
     * @param customKeywords 自定义关键词，为空则使用默认关键词
     * @return 是否开始成功
     */
    fun startListening(
        context: Context,
        listener: KeywordDetectionListener,
        customKeywords: String = ""
    ): Boolean {

        this.listener = listener

        // 检查权限
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            listener.onError(ErrorCode.NO_PERMISSION, "没有录音权限")
            return false
        }

        // 检查是否已经在录音
        if (isRecording) {
            Log.w(TAG, "已经在录音中")
            return true
        }

        // 确保模型已初始化
        if (kws == null) {
            listener.onError(ErrorCode.INIT_FAILED, "模型未初始化")
            return false
        }

        try {
            // 如果有自定义关键词，重新创建stream
            if (customKeywords.isNotEmpty()) {
                stream?.release()
                stream = kws?.createStream(customKeywords)
                if (stream?.ptr == 0L) {
                    listener.onError(ErrorCode.INIT_FAILED, "创建关键词流失败")
                    return false
                }
            }

            // 初始化麦克风
            if (!initMicrophone()) {
                listener.onError(ErrorCode.RECORDING_FAILED, "麦克风初始化失败")
                return false
            }

            // 开始录音
            audioRecord?.startRecording()
            isRecording = true

            // 启动处理线程
            recordingThread = thread(start = true) {
                processSamples()
            }

            Log.i(TAG, "开始监听麦克风")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "开始监听失败", e)
            listener.onError(ErrorCode.RECORDING_FAILED, "开始监听失败: ${e.message}")
            return false
        }
    }

    /**
     * 停止监听麦克风
     */
    fun stopListening() {
        if (!isRecording) {
            Log.w(TAG, "当前没有在录音")
            return
        }

        try {
            isRecording = false

            // 停止录音
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // 等待线程结束
            recordingThread?.join(1000)
            recordingThread = null

            Log.i(TAG, "停止监听麦克风")

        } catch (e: Exception) {
            Log.e(TAG, "停止监听失败", e)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopListening()

        stream?.release()
        stream = null

        kws?.release()
        kws = null

        _modelState.value = ModelState.NotInitialized
        listener = null

        Log.i(TAG, "释放资源完成")
    }

    /**
     * 检查是否正在监听
     */
    fun isListening(): Boolean = isRecording

    /**
     * 初始化麦克风
     */
    @SuppressLint("MissingPermission")
    private fun initMicrophone(): Boolean {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE ||
                minBufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "获取缓冲区大小失败")
                return false
            }

            audioRecord = AudioRecord(
                AUDIO_SOURCE,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )

            return audioRecord?.state == AudioRecord.STATE_INITIALIZED

        } catch (e: Exception) {
            Log.e(TAG, "初始化麦克风失败", e)
            return false
        }
    }

    /**
     * 处理音频样本
     */
    private fun processSamples() {
        Log.i(TAG, "开始处理音频样本")

        val interval = 0.1f // 100ms
        val bufferSize = (interval * SAMPLE_RATE).toInt()
        val buffer = ShortArray(bufferSize)

        while (isRecording) {
            try {
                val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (samplesRead > 0) {
                    // 转换为浮点数组
                    val samples = FloatArray(samplesRead) { buffer[it] / 32768.0f }

                    // 提供给KWS处理
                    stream?.acceptWaveform(samples, SAMPLE_RATE)

                    // 检查是否有关键词检测结果
                    val kwsInstance = kws
                    val streamInstance = stream
                    if (kwsInstance != null && streamInstance != null) {
                        while (kwsInstance.isReady(streamInstance)) {
                            kwsInstance.decode(streamInstance)

                            val result = kwsInstance.getResult(streamInstance)

                            if (result.keyword.isNotBlank()) {
                                // 重置流以准备下次检测
                                kwsInstance.reset(streamInstance)

                                // 通知检测到关键词
                                listener?.onKeywordDetected(result.keyword)

                                Log.i(TAG, "检测到关键词: ${result.keyword}")
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理音频样本时出错", e)
                listener?.onError(ErrorCode.RECORDING_FAILED, "处理音频出错: ${e.message}")
                break
            }
        }

        Log.i(TAG, "音频处理线程结束")
    }
}