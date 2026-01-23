package com.aotuman.baobaoai.utils

import android.content.Context
import android.util.Log
import com.aotuman.baobaoai.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

object VoiceAssistantManager {
    private const val TAG = "VoiceAssistantManager"
    
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
    private var onCommand: ((String) -> Unit)? = null
    private var onSleep: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    
    // 初始化
    suspend fun initialize(context: Context) {
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
        onCommandCallback: (String) -> Unit,
        onSleepCallback: () -> Unit,
        onErrorCallback: (String) -> Unit
    ) {
        onWakeUp = onWakeUpCallback
        onCommand = onCommandCallback
        onSleep = onSleepCallback
        onError = onErrorCallback
        
        // 开始关键词监听
        startKwsListening(context)
    }
    
    // 停止语音助手
    suspend fun stopAssistant() {
        // 停止所有监听
        SherpaKwsManager.stopListening()
        SherpaStreamingManager.stopListening()
        
        // 重置状态
        _state.value = AssistantState.Sleeping
    }
    
    // 开始关键词监听
    private fun startKwsListening(context: Context) {
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
    }
    
    // 唤醒
    private fun wakeUp(context: Context) {
        Log.i(TAG, "唤醒语音助手")
        _state.value = AssistantState.Listening
        
        // 通知唤醒
        onWakeUp?.invoke()
        
        // 停止关键词监听
        SherpaKwsManager.stopListening()
        
        // 开始ASR监听
        startAsrListening(context)
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
        
        // 通知命令
        onCommand?.invoke(command)
        
        // 处理完成后继续监听
        _state.value = AssistantState.Listening
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
}
