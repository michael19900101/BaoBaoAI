package com.aotuman.baobaoai

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.aotuman.baobaoai.ui.theme.BaoBaoAITheme
import com.aotuman.baobaoai.utils.SherpaKwsManager
import com.aotuman.baobaoai.utils.SherpaStreamingManager
import com.aotuman.baobaoai.utils.SystemCtrlUtil
import com.aotuman.baobaoai.utils.VoiceAssistantManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS
    )

    // 模型初始化状态
    private var isModelInitialized = mutableStateOf(false)
    private var isInitializing = mutableStateOf(false)

    // 防抖处理：记录上次点击时间
    private var lastClickTime = 0L
    private val CLICK_INTERVAL = 1000L // 1秒防抖间隔

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            checkOverlayPermission()
        } else {
            Toast.makeText(this, "需要录音权限才能使用语音助手", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initVoiceAssistant()
        enableEdgeToEdge()
        setContent {
            BaoBaoAITheme {
                Scaffold(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        onStartAssistant = { checkPermissions() },
                        isModelInitialized = isModelInitialized.value,
                        isInitializing = isInitializing.value,
                        modifier = Modifier.padding(it)
                    )
                }
            }
        }
    }

    private fun initVoiceAssistant(){
        lifecycleScope.launch {
            try {
                isInitializing.value = true
                Log.d("MainActivity", "Initializing voice assistant models...")

                // 初始化 Sherpa 模型
                VoiceAssistantManager.initialize(this@MainActivity)

                // 等待模型初始化完成
                val kwsReady = SherpaKwsManager.modelState.value is SherpaKwsManager.ModelState.Ready
                val streamingReady = SherpaStreamingManager.modelState.value is SherpaStreamingManager.ModelState.Ready

                Log.d("MainActivity", "KWS model ready: $kwsReady, Streaming model ready: $streamingReady")

                if (kwsReady && streamingReady) {
                    // 初始化 ModelClient
                    Log.d("MainActivity", "Initializing ModelClient...")
                    Log.d("MainActivity", "ModelClient initialized and set to AutoGLMService successfully")

                    isModelInitialized.value = true
                    isInitializing.value = false
                    Log.d("MainActivity", "All models initialized successfully")
                } else {
                    // 等待一段时间再检查
                    delay(2000)
                    val kwsReady2 = SherpaKwsManager.modelState.value is SherpaKwsManager.ModelState.Ready
                    val streamingReady2 = SherpaStreamingManager.modelState.value is SherpaStreamingManager.ModelState.Ready

                    if (kwsReady2 && streamingReady2) {
                        isModelInitialized.value = true
                        isInitializing.value = false
                        Log.d("MainActivity", "All models initialized successfully (after delay)")
                    } else {
                        isInitializing.value = false
                        val errorMsg = "模型初始化失败: KWS=$kwsReady2, Streaming=$streamingReady2"
                        Log.e("MainActivity", errorMsg)
                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                isInitializing.value = false
                e.printStackTrace()
                Log.e("MainActivity", "Error initializing voice assistant: ${e.message}", e)
                Toast.makeText(this@MainActivity, "语音助手初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun checkPermissions() {
        // 防抖处理：检查点击间隔
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_INTERVAL) {
            return
        }
        lastClickTime = currentTime

        // 先检查模型是否已初始化
        if (!isModelInitialized.value) {
            Toast.makeText(this, "请等待模型初始化完成", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasPermissions = permissions.all {
                ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }

            if (hasPermissions) {
                checkOverlayPermission()
            } else {
                requestPermissionLauncher.launch(permissions)
            }
        }
    }
    
    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        } else {
            startAutoGLMService()
        }
    }
    
    /**
     * 检查AccessibilityService是否已启用
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, AutoGLMService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponentName = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponentName != null && enabledComponentName == expectedComponentName) {
                return true
            }
        }
        return false
    }

    /**
     * 跳转到系统无障碍设置页面
     */
    private fun navigateToAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST_CODE)
    }

    private fun startAutoGLMService() {
        if (!isAccessibilityServiceEnabled()) {
            // 尝试使用 root 权限自动启用无障碍服务
            if (SystemCtrlUtil.sysHasRootPermission()) {
                val serviceName = "$packageName/${AutoGLMService::class.java.canonicalName}"

//                 todo:这里有个bug,一定先通过 "打开 -> 禁用 -> 打开"的步骤才能成功打开AutoGLMService无障碍服务
//                val op1 = SystemCtrlUtil.enableAccessibilityService(this, serviceName)
//                val op2 = SystemCtrlUtil.disableAccessibilityService(this, serviceName)
                val op3 = SystemCtrlUtil.enableAccessibilityService(this, serviceName)
                if (op3) {
                    Toast.makeText(this, "已自动启用无障碍服务", Toast.LENGTH_SHORT).show()
                    // 等待一下让服务启动
                    Thread.sleep(500)
                } else {
                    // 自动启用失败，引导用户手动设置
                    Toast.makeText(this, "自动启用失败，请在无障碍设置中手动启用", Toast.LENGTH_LONG).show()
                    navigateToAccessibilitySettings()
                }
            } else {
                // 没有 root 权限，引导用户手动设置
                Toast.makeText(this, "请在无障碍设置中启用BaoBao AI语音助手", Toast.LENGTH_LONG).show()
                navigateToAccessibilitySettings()
            }
        } else {
            // AccessibilityService已启用，启动服务
            Toast.makeText(this, "语音助手已启动", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startAutoGLMService()
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能使用语音助手", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == ACCESSIBILITY_PERMISSION_REQUEST_CODE) {
            // 从无障碍设置返回，检查是否已启用
            if (isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "语音助手已启动", Toast.LENGTH_SHORT).show()
            } else {
                // 未启用，提示用户
                Toast.makeText(this, "未启用无障碍服务，语音助手无法正常工作", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val service = AutoGLMService.getInstance()
        service?.stopTask()
        lifecycleScope.launch {
            service?.floatingWindowController?.forceDismiss()
        }
    }
    
    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
        private const val ACCESSIBILITY_PERMISSION_REQUEST_CODE = 1002
    }
}

@Composable
fun MainScreen(
    onStartAssistant: () -> Unit,
    isModelInitialized: Boolean,
    isInitializing: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "BaoBao AI 语音助手",
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 显示初始化状态
        when {
            isInitializing -> {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                Text(
                    text = "正在加载模型，请稍候...",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(onClick = {}, enabled = false) {
                    Text(text = "启动语音助手")
                }
            }
            isModelInitialized -> {
                Button(onClick = onStartAssistant) {
                    Text(text = "启动语音助手")
                }
            }
            else -> {
                Button(onClick = onStartAssistant, enabled = false) {
                    Text(text = "启动语音助手")
                }
                Text(
                    text = "模型初始化失败，请重试",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}