package com.aotuman.baobaoai.action

import android.content.Intent
import android.util.Log
import com.aotuman.baobaoai.AutoGLMService
import kotlinx.coroutines.delay

class ActionExecutor(private val service: AutoGLMService) {

    suspend fun execute(action: Action): Boolean {
        return when (action) {
            is Action.Tap -> {
                Log.d("ActionExecutor", "Tapping ${action.x}, ${action.y}")
                val success = service.performTap(action.x.toFloat(), action.y.toFloat())
                delay(1000)
                success
            }
            is Action.DoubleTap -> {
                Log.d("ActionExecutor", "Double Tapping ${action.x}, ${action.y}")
                // Execute two taps with a short delay
                val success1 = service.performTap(action.x.toFloat(), action.y.toFloat())
                delay(150) 
                val success2 = service.performTap(action.x.toFloat(), action.y.toFloat())
                delay(1000)
                success1 && success2
            }
            is Action.LongPress -> {
                Log.d("ActionExecutor", "Long Pressing ${action.x}, ${action.y}")
                val success = service.performLongPress(action.x.toFloat(), action.y.toFloat())
                delay(1000)
                success
            }
            is Action.Swipe -> {
                Log.d("ActionExecutor", "Swiping ${action.startX},${action.startY} -> ${action.endX},${action.endY}")
                val success = service.performSwipe(
                    action.startX.toFloat(), action.startY.toFloat(),
                    action.endX.toFloat(), action.endY.toFloat()
                )
                delay(1000)
                success
            }
            is Action.Type -> {
                Log.d("ActionExecutor", "Typing ${action.text}")
                // 简化处理：暂时不支持自动输入文本，因为TextInputHandler不存在
                // 可以在后续实现中添加此功能
                true
            }
            is Action.Launch -> {
                Log.d("ActionExecutor", "Launching ${action.appName}")
                // 简化处理：直接将应用名称作为包名尝试启动
                try {
                    val intent = service.packageManager.getLaunchIntentForPackage(action.appName)
                    if (intent != null) {
                        Log.d("ActionExecutor", "Found intent for ${action.appName}, starting activity...")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        service.startActivity(intent)
                        Log.d("ActionExecutor", "Activity started successfully")
                        delay(2000)
                        true
                    } else {
                        Log.e("ActionExecutor", "Launch intent is null for ${action.appName}")
                        false
                    }
                } catch (e: Exception) {
                    Log.e("ActionExecutor", "Failed to start activity: ${e.message}")
                    false
                }
            }
            is Action.Back -> {
                service.performGlobalBack()
                delay(1000)
                true
            }
            is Action.Home -> {
                service.performGlobalHome()
                delay(1000)
                true
            }
            is Action.Wait -> {
                delay(action.durationMs)
                true
            }
            is Action.Finish -> {
                Log.i("ActionExecutor", "Task Finished: ${action.message}")
                true
            }
            is Action.Error -> {
                Log.e("ActionExecutor", "Error: ${action.reason}")
                false
            }
            Action.Unknown -> false
        }
    }
}