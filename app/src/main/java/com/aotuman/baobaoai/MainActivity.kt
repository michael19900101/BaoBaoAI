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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.aotuman.baobaoai.ui.theme.BaoBaoAITheme
import com.aotuman.baobaoai.utils.SherpaVadManager
import com.aotuman.baobaoai.utils.SherpaKwsManager
import com.aotuman.baobaoai.utils.SherpaModelManager
import com.aotuman.baobaoai.utils.SherpaStreamingManager
import com.aotuman.baobaoai.utils.SpeechRecognizerManager
import com.aotuman.baobaoai.utils.VoiceAssistantManager
import com.aotuman.baobaoai.utils.VoiceAssistantTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    
    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS
    )
    
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
                        modifier = Modifier.padding(it)
                    )
                }
            }
        }
    }

    private fun initVoiceAssistant(){
        lifecycleScope.launch {
            try {
                VoiceAssistantManager.initialize(this@MainActivity)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun checkPermissions() {
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
            // AccessibilityService未启用，引导用户到设置页面
            Toast.makeText(this, "请在无障碍设置中启用BaoBao AI语音助手", Toast.LENGTH_LONG).show()
            navigateToAccessibilitySettings()
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
fun MainScreen(onStartAssistant: () -> Unit, modifier: Modifier = Modifier) {
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
        Button(onClick = onStartAssistant) {
            Text(text = "启动语音助手")
        }
    }
}