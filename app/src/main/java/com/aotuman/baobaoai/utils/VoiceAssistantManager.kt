package com.aotuman.baobaoai.utils

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.aotuman.baobaoai.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

object VoiceAssistantManager {
    private const val TAG = "VoiceAssistantManager"

    // MediaPlayer 用于播放音效
    private var mediaPlayer: MediaPlayer? = null

    // 保存 Context 引用
    private var appContext: Context? = null

    // 助手状态
    sealed class AssistantState {
        object Sleeping : AssistantState() // 休眠状态，等待唤醒
        object Listening : AssistantState() // 唤醒后监听状态
        object Processing : AssistantState() // 处理语音输入状态
    }

    // 状态管理
    private val _state = MutableStateFlow<AssistantState>(AssistantState.Sleeping)
    val state: StateFlow<AssistantState> = _state

    // 回调
    private var onWakeUp: (() -> Unit)? = null
    private var onListening: ((String) -> Unit)? = null
    private var onCommand: ((String) -> Unit)? = null
    private var onSleep: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null


    // 初始化
    suspend fun initialize(context: Context) {
        appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            // 初始化KWS模型
            SherpaKwsManager.initialize(context)

            // 初始化ASR模型
            SherpaStreamingManager.initialize(context)
        }
    }
    
    // 开始语音助手
    fun startAssistant(
        context: Context,
        onWakeUpCallback: () -> Unit,
        onListeningCallback: ((String) -> Unit)? = null,
        onCommandCallback: (String) -> Unit,
        onSleepCallback: () -> Unit,
        onErrorCallback: (String) -> Unit
    ) {
        try {
            onWakeUp = onWakeUpCallback
            onListening = onListeningCallback
            onCommand = onCommandCallback
            onSleep = onSleepCallback
            onError = onErrorCallback

            // 开始关键词监听
            startKwsListening(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting assistant: ${e.message}", e)
            onError?.invoke("启动语音助手失败: ${e.message}")
        }
    }
    
    // 停止语音助手
    fun stopAssistant() {
        // 停止所有监听
        SherpaKwsManager.stopListening()
        SherpaStreamingManager.stopListening()
        
        // 重置状态
        _state.value = AssistantState.Sleeping
    }
    
    // 开始关键词监听
    private fun startKwsListening(context: Context) {
        try {
            Log.i(TAG, "开始关键词监听")
            _state.value = AssistantState.Sleeping

            SherpaKwsManager.startListening(
                context = context,
                listener = object : SherpaKwsManager.KeywordDetectionListener {
                    override fun onKeywordDetected(keyword: String) {
                        Log.i(TAG, "检测到关键词: $keyword")
                        // 唤醒
                        wakeUp(context)
                    }

                    override fun onError(errorCode: Int, errorMessage: String) {
                        Log.e(TAG, "KWS错误: $errorMessage")
                        onError?.invoke("关键词监听错误: $errorMessage")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting KWS listening: ${e.message}", e)
            onError?.invoke("启动关键词监听失败: ${e.message}")
        }
    }
    
    // 唤醒
    private fun wakeUp(context: Context) {
        Log.i(TAG, "唤醒语音助手")
        _state.value = AssistantState.Listening

        // 播放唤醒音
        playWakeUpSound(context)

        // 通知唤醒
        onWakeUp?.invoke()

        // 停止关键词监听
        SherpaKwsManager.stopListening()

        // 开始ASR监听
        startAsrListening(context)
    }

    // 播放唤醒音
    private fun playWakeUpSound(context: Context) {
        try {
            // 释放之前的播放器
            releaseMediaPlayer()

            val afd = context.assets.openFd("sherpa-model/voice/wake_up.m4a")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                }
                prepareAsync()
                setOnPreparedListener {
                    it.start()
                    Log.i(TAG, "唤醒音开始播放")
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "播放唤醒音失败 - what: $what, extra: $extra")
                    releaseMediaPlayer()
                    false
                }
            }
            afd.close()
        } catch (e: Exception) {
            Log.e(TAG, "播放唤醒音异常: ${e.message}", e)
            releaseMediaPlayer()
        }
    }

    // 释放 MediaPlayer
    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "释放MediaPlayer异常: ${e.message}")
        }
    }
    
    // 开始ASR监听
    private fun startAsrListening(context: Context) {
        Log.i(TAG, "开始ASR监听")

        SherpaStreamingManager.startListening(context, object : SherpaStreamingManager.StreamingDetectionListener {
            override fun onDetected(isEndpoint: Boolean, text: String) {
                if (isEndpoint) {
                    Log.i(TAG, "识别结果: $text")
                    processCommand(text)
                    sleep()
                } else {
                    onListening?.invoke(text)
                }
            }

            override fun onError(errorCode: Int, errorMessage: String) {
                onError?.invoke("语音监听错误: $errorMessage")
            }

        })
    }
    
    // 处理命令
    private fun processCommand(command: String) {
        Log.i(TAG, "处理命令: $command")
        _state.value = AssistantState.Processing

        // 播放成功音
        appContext?.let { playSuccessSound(it) }

        // 通知命令
        onCommand?.invoke(command)

        // 处理完成后继续监听
        _state.value = AssistantState.Listening
    }

    // 播放成功音
    private fun playSuccessSound(context: Context) {
        try {
            // 释放之前的播放器
            releaseMediaPlayer()

            val afd = context.assets.openFd("sherpa-model/voice/ok.m4a")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                }
                prepareAsync()
                setOnPreparedListener {
                    it.start()
                    Log.i(TAG, "成功音开始播放")
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "播放成功音失败 - what: $what, extra: $extra")
                    releaseMediaPlayer()
                    false
                }
            }
            afd.close()
        } catch (e: Exception) {
            Log.e(TAG, "播放成功音异常: ${e.message}", e)
            releaseMediaPlayer()
        }
    }
    
    // 休眠
    private fun sleep() {
        Log.i(TAG, "语音助手休眠")
        
        // 停止ASR监听
        SherpaStreamingManager.stopListening()
        
        // 重置状态
        _state.value = AssistantState.Sleeping
        
        // 通知休眠
        onSleep?.invoke()
        
        // 重新开始关键词监听
        val context = MyApplication.instance
        startKwsListening(context)
    }

    // 播放任务完成音效（公开方法供外部调用）
    fun playTaskCompleteSound() {
        appContext?.let { playTaskCompleteSound(it) }
    }

    // 播放任务完成音效
    private fun playTaskCompleteSound(context: Context) {
        try {
            // 释放之前的播放器
            releaseMediaPlayer()

            val afd = context.assets.openFd("sherpa-model/voice/task_complete.m4a")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                }
                prepareAsync()
                setOnPreparedListener {
                    it.start()
                    Log.i(TAG, "任务完成音开始播放")
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "播放任务完成音失败 - what: $what, extra: $extra")
                    releaseMediaPlayer()
                    false
                }
            }
            afd.close()
        } catch (e: Exception) {
            Log.e(TAG, "播放任务完成音异常: ${e.message}", e)
            releaseMediaPlayer()
        }
    }
}
