package com.aotuman.baobaoai.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VoiceAssistantTest {
    companion object {
        private const val TAG = "VoiceAssistantTest"
    }
    
    fun testVoiceAssistant(context: Context) {
        Log.i(TAG, "开始测试语音助手")
        
        // 初始化语音助手
        CoroutineScope(Dispatchers.IO).launch {
            try {
                VoiceAssistantManager.initialize(context)
                Log.i(TAG, "语音助手初始化成功")
                
                // 开始语音助手
                VoiceAssistantManager.startAssistant(
                    context = context,
                    onWakeUpCallback = {
                        Log.i(TAG, "=== 语音助手唤醒 ===")
                        // 这里可以添加唤醒后的UI反馈
                    },
                    onCommandCallback = {
                        Log.i(TAG, "=== 接收到命令: $it ===")
                        // 这里可以添加命令处理逻辑
                    },
                    onSleepCallback = {
                        Log.i(TAG, "=== 语音助手休眠 ===")
                        // 这里可以添加休眠后的UI反馈
                    },
                    onErrorCallback = {
                        Log.e(TAG, "=== 语音助手错误: $it ===")
                        // 这里可以添加错误处理逻辑
                    }
                )
                
                Log.i(TAG, "语音助手启动成功")
                Log.i(TAG, "请说 '小爱小爱' 来唤醒语音助手")
                Log.i(TAG, "唤醒后请说出您的命令")
                
            } catch (e: Exception) {
                Log.e(TAG, "语音助手初始化失败: ${e.message}", e)
            }
        }
    }
    
    suspend fun stopVoiceAssistant() {
        VoiceAssistantManager.stopAssistant()
        Log.i(TAG, "语音助手已停止")
    }
}
