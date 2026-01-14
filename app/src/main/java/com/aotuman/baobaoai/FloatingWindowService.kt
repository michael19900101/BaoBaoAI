package com.aotuman.baobaoai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

import com.aotuman.baobaoai.utils.SherpaModelManager
import com.aotuman.baobaoai.utils.SpeechRecognizerManager
import kotlinx.coroutines.withContext

class FloatingWindowService : Service() {

    private lateinit var floatingWindowController: FloatingWindowController
    private var speechRecognizerManager: SpeechRecognizerManager? = null
    
    // 悬浮窗状态
    private var isExpanded = false
    private var speechText = ""
    private var isListening = false
    
    private val TAG = "FloatingWindowService"
    
    // Coroutine scope for managing async operations
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    
    override fun onCreate() {
        super.onCreate()
        
        // 初始化FloatingWindowController
        floatingWindowController = FloatingWindowController(this)
        
        // 初始化语音识别管理器
        initializeSpeechRecognizer()
        
        // 启动悬浮窗
        serviceScope.launch {
            floatingWindowController.showAndWaitForLayout(
                onStop = { /* 处理停止按钮点击 */ },
                isRunning = true,
                onStartListening = { startListening() },
                onStopListening = { stopListening() }
            )
        }
        
        startForegroundService()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    private fun initializeSpeechRecognizer() {
        try {
            // 初始化模型
            serviceScope.launch(Dispatchers.IO) {
                SherpaModelManager.initModel(this@FloatingWindowService)
                
                // 模型初始化成功后，创建SpeechRecognizerManager
                withContext(Dispatchers.Main) {
                    speechRecognizerManager = SpeechRecognizerManager(this@FloatingWindowService)
                    floatingWindowController.updateStatus("语音识别就绪")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing speech recognizer: ${e.message}")
            speechText = "语音识别初始化失败"
            floatingWindowController.updateStatus("语音识别初始化失败")
            speechRecognizerManager = null
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 使用FloatingWindowController移除窗口
        serviceScope.launch {
            floatingWindowController.dismiss()
        }
        speechRecognizerManager?.destroy()
    }
    
    // 语音识别控制方法
    private fun startListening() {
        if (!isListening && speechRecognizerManager != null) {
            try {
                // 检查权限
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "RECORD_AUDIO permission not granted")
                    isListening = false
                    speechText = "录音权限未授予"
                    floatingWindowController.updateStatus("录音权限未授予")
                    floatingWindowController.setTaskRunning(false)
                    floatingWindowController.setListening(false)
                    return
                }
                
                isListening = true
                speechText = "正在聆听..."
                floatingWindowController.updateStatus("正在聆听...")
                floatingWindowController.setTaskRunning(true)
                floatingWindowController.setListening(true)

                speechRecognizerManager?.startListening(
                    onResultCallback = { result ->
                        speechText = result
                        isListening = false
                        floatingWindowController.updateStatus(result)
                        floatingWindowController.updateSpeechText(result)
                        floatingWindowController.setTaskRunning(false)
                        floatingWindowController.setListening(false)
                    },
                    onErrorCallback = { error ->
                        Log.e(TAG, "Speech recognition error: $error")
                        speechText = "识别失败，请重试"
                        isListening = false
                        floatingWindowController.updateStatus("识别失败，请重试")
                        floatingWindowController.setTaskRunning(false)
                        floatingWindowController.setListening(false)
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error starting speech recognition: ${e.message}")
                isListening = false
                speechText = "开始录音失败"
                floatingWindowController.updateStatus("开始录音失败")
                floatingWindowController.setTaskRunning(false)
                floatingWindowController.setListening(false)
            }
        } else {
            Log.e(TAG, "Speech recognition is not available or already listening")
        }
    }
    
    private fun stopListening() {
        if (isListening && speechRecognizerManager != null) {
            serviceScope.launch(Dispatchers.IO) {
                speechRecognizerManager?.stopListening()
                withContext(Dispatchers.Main) {
                    isListening = false
                    floatingWindowController.setTaskRunning(false)
                    floatingWindowController.setListening(false)
                }
            }
        }
    }
    
    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_window_channel",
                "悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = Notification.Builder(this, "floating_window_channel")
            .setContentTitle("BaoBao AI 语音助手")
            .setContentText("正在运行")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
        
        startForeground(1, notification)
    }
    

}
