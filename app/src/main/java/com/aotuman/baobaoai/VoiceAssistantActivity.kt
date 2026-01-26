package com.aotuman.baobaoai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aotuman.baobaoai.ui.AssistantState
import com.aotuman.baobaoai.ui.FloatingWindowAndBall
import com.aotuman.baobaoai.ui.theme.BaoBaoAITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class YuanBaoVoiceAssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BaoBaoAITheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    YuanBaoVoiceAssistantScreen()
                }
            }
        }
    }
}

@Composable
fun YuanBaoVoiceAssistantScreen() {
    var currentState by remember { mutableStateOf<AssistantState>(AssistantState.Idle) }
    val coroutineScope = rememberCoroutineScope()
    
    // 渐变背景
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF667eea),
            Color(0xFF764ba2)
        )
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题
            Text(
                text = "🎤 AI语音助手 - 悬浮球演示",
                fontSize = 28.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            
            Text(
                text = "点击下方按钮体验不同交互状态",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(bottom = 30.dp)
            )
            
            // 手机模拟器
            PhoneMockup(
                currentState = currentState,
                onStateChange = { currentState = it },
                modifier = Modifier.padding(bottom = 40.dp)
            )
            
            // 控制面板
            ControlPanel(
                currentState = currentState,
                onStateChange = { newState ->
                    currentState = newState
                    // 成功/错误状态自动重置
                    if (newState is AssistantState.Success || newState is AssistantState.Error) {
                        coroutineScope.launch {
                            delay(2000)
                            if (currentState == newState) {
                                currentState = AssistantState.Idle
                            }
                        }
                    }
                    // 监听状态模拟语音输入
                    if (newState is AssistantState.Listening) {
                        coroutineScope.launch {
                            simulateVoiceInput(newState.text) { text ->
                                currentState = AssistantState.Listening(text)
                            }
                        }
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(30.dp))
            
            // 状态指示器
            StatusIndicator()
        }
    }
}

@Composable
fun PhoneMockup(
    currentState: AssistantState,
    onStateChange: (AssistantState) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(320.dp)
            .height(650.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(Color(0xFF121212))
            .padding(12.dp)
            .shadow(20.dp, RoundedCornerShape(40.dp))
    ) {
        // 手机屏幕
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF1a1a1a),
                            Color(0xFF2d2d2d)
                        )
                    )
                )
        ) {
            // 应用内容（模拟微信聊天）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = "当前界面：微信聊天",
                    fontSize = 18.sp,
                    color = Color.White.copy(alpha = 0.87f),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
                
                Text(
                    text = "这是模拟的应用界面，悬浮球位于右下角。",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                
                Text(
                    text = "点击右侧控制按钮，查看悬浮球不同状态。",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                
                Text(
                    text = "悬浮窗会自动在2秒后收起（成功/错误状态）。",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 30.dp)
                )
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(0.6f)
                ) {
                    Text(
                        text = "模拟对话：",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    
                    Text(
                        text = "👤 张三：晚上一起吃饭？",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "👤 你：好的，几点？",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text(
                        text = "👤 张三：7点老地方见",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            
            // 悬浮窗和悬浮球
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                FloatingWindowAndBall(
                    state = currentState,
                    onStateChange = onStateChange
                )
            }
        }
    }
}

@Composable
fun ControlPanel(
    currentState: AssistantState,
    onStateChange: (AssistantState) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.95f))
            .padding(30.dp)
    ) {
        Text(
            text = "控制面板",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 20.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            ControlButton(
                icon = "○",
                text = "待机状态",
                isActive = currentState is AssistantState.Idle,
                backgroundColor = Color(0xFFf5f5f5),
                onClick = { onStateChange(AssistantState.Idle) },
                modifier = Modifier.weight(1f)
            )
            
            ControlButton(
                icon = "🎤",
                text = "语音监听",
                isActive = currentState is AssistantState.Listening,
                backgroundColor = Color(0xFF2196f3),
                onClick = { onStateChange(AssistantState.Listening()) },
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(15.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            ControlButton(
                icon = "⏳",
                text = "AI处理中",
                isActive = currentState is AssistantState.Processing,
                backgroundColor = Color(0xFF9c27b0),
                onClick = { onStateChange(AssistantState.Processing("正在打开微信...")) },
                modifier = Modifier.weight(1f)
            )
            
            ControlButton(
                icon = "✅",
                text = "执行成功",
                isActive = currentState is AssistantState.Success,
                backgroundColor = Color(0xFF4caf50),
                onClick = { onStateChange(AssistantState.Success("微信已打开")) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ControlButton(
    icon: String,
    text: String,
    isActive: Boolean,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "buttonScale"
    )
    
    Button(
        onClick = onClick,
        modifier = modifier
            .scale(scale)
            .height(80.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = if (backgroundColor == Color(0xFFf5f5f5)) Color(0xFF333333) else Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (isActive) 8.dp else 4.dp,
            pressedElevation = 2.dp
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = icon,
                fontSize = 20.sp
            )
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun StatusIndicator(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            StatusItem(
                dotColor = Color(255, 255, 255, alpha = 77),
                text = "待机监听"
            )
            StatusItem(
                dotColor = Color(0xFF2196f3),
                text = "语音输入"
            )
            StatusItem(
                dotColor = Color(0xFF9c27b0),
                text = "AI处理"
            )
            StatusItem(
                dotColor = Color(0xFF4caf50),
                text = "执行成功"
            )
        }
    }
}

@Composable
fun StatusItem(
    dotColor: Color,
    text: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusDot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusAlpha"
    )
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(dotColor)
                .alpha(alpha)
        )
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

// 模拟语音输入效果
suspend fun simulateVoiceInput(
    currentText: String,
    onTextUpdate: (String) -> Unit
) {
    val fullText = "打开微信给张三发消息说晚上开会"
    var index = currentText.length
    
    while (index < fullText.length) {
        delay(100)
        onTextUpdate(fullText.substring(0, index + 1))
        index++
    }
    
    // 语音输入完成后，自动切换到处理状态
    delay(500)
    // 这里不自动切换，让用户手动控制
}
