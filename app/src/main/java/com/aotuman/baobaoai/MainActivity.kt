package com.aotuman.baobaoai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import com.aotuman.baobaoai.utils.SherpaKwsManager
import com.aotuman.baobaoai.utils.SherpaModelManager
import com.aotuman.baobaoai.utils.SpeechRecognizerManager
import kotlinx.coroutines.Dispatchers
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
            startFloatingWindowService()
        }
    }
    
    private fun startFloatingWindowService() {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "语音助手已启动", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingWindowService()
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能使用语音助手", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
    }

    private fun initializeKws() {
        try {
            // 创建KwsManager
            lifecycleScope.launch(Dispatchers.IO) {
                SherpaKwsManager.initialize(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (SherpaKwsManager.modelState.value == SherpaKwsManager.ModelState.Ready) {
                        // 启动SherpaKwsManager
                        SherpaKwsManager.startListening(
                            this@MainActivity,
                            object : SherpaKwsManager.KeywordDetectionListener {

                                override fun onKeywordDetected(keyword: String) {
                                    Log.e("jbjb", "Keyword detected: $keyword")
                                }

                                override fun onError(
                                    errorCode: Int,
                                    errorMessage: String
                                ) {
                                    Log.e("jbjb", "Error in KwsManager: $errorCode, $errorMessage")
                                }
                            }
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("jbjb", "Error initializing SherpaKwsManager: ${e.message}")
        }
    }

    private fun initializeSpeechRecognizer() {
        try {
            // 初始化模型
            lifecycleScope.launch(Dispatchers.IO) {
                SherpaModelManager.initModel(this@MainActivity)

                var speechRecognizerManager: SpeechRecognizerManager? = null
                // 模型初始化成功后，创建SpeechRecognizerManager
                withContext(Dispatchers.Main) {
                    speechRecognizerManager = SpeechRecognizerManager(this@MainActivity)
                    speechRecognizerManager.startListening(
                        onResultCallback = { result ->
                            Log.e("jbjb", "Speech recognition result: $result")
                        },
                        onErrorCallback = { error ->
                            Log.e("jbjb", "Speech recognition error: $error")
                        }
                    )
                }

            }
        } catch (e: Exception) {
            Log.e("jbjb", "Error initializing speech recognizer: ${e.message}")
        }
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